package com.zongyi.box.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.shuyu.gsyvideoplayer.GSYVideoManager
import com.shuyu.gsyvideoplayer.listener.VideoAllCallBack
import com.shuyu.gsyvideoplayer.player.PlayerFactory
import com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer
import tv.danmaku.ijk.media.exo2.Exo2PlayerManager
import com.zongyi.box.R
import com.zongyi.box.data.BrowserHeaders
import com.zongyi.box.data.Episode
import com.zongyi.box.data.HistoryEntry
import com.zongyi.box.data.SiteApi
import com.zongyi.box.data.Store
import com.zongyi.box.data.VodDetail
import com.zongyi.box.data.runSuspendCatching
import com.zongyi.box.databinding.ActivityPlayerBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * 播放器（GSYVideoPlayer 14.0 + Media3/Exo 内核）。
 *
 * GSY 提供的成熟播放 UI：亮度/音量/快进手势、双击暂停、进度条、
 * 缓冲提示、播放暂停、静音、错误重试界面。
 * 本类保留全部自有功能：断点续播、倍速、上下集、线路切换、选集、
 * 观看历史、播放错误自动重试、浏览器伪装请求头、播放不息屏。
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlayerBinding

    private var vodId = -1
    private var sid = -1
    private var nid = -1
    private var title = ""
    private var srcKey = com.zongyi.box.data.Src.JUZI.key
    private var detail: VodDetail? = null
    private var resumed = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
    private var speedIndex = 2

    // 播放错误自动重试
    private var retryCount = 0
    private var retryPending = false
    private val MAX_RETRY = 3

    // 当前集的播放地址：用于区分"当前集"的完成/错误回调与切换时旧播放器释放产生的伪回调
    private var currentUrl: String? = null

    // 切集防重入：从发起切换到新流真正开播期间，忽略再次的切集请求
    private var switching = false

    // 历史进度轮询
    private var pollTask: Runnable? = null

    @SuppressLint("SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

        // GSY 内核切换为 Media3/Exo（HLS 解码、清晰度自适应、断点续播能力最强）
        // 全部 player 全局配置逐项兜底：个别 TV ROM 缺组件时降级为默认行为，不崩
        runCatching { PlayerFactory.setPlayManager(Exo2PlayerManager::class.java) }

        // 页面本身已是全屏横屏，隐藏 GSY 自带全屏按钮，
        // 避免触发 startWindowFullscreen 跳转未配置的 GSYFullActivity 而崩溃
        runCatching { b.player.getFullscreenButton().visibility = View.GONE }
        // 关闭"当前为移动网络"提示弹窗，避免播放被弹窗打断
        runCatching { b.player.setNeedShowWifiTip(false) }
        // TV：关掉 GSY 自带控制栏自动隐藏（会与遥控器手动唤出互相打断、抢焦点），
        // 改由本类键控逻辑统一显隐（4 秒无操作隐藏，焦点停在按钮上时不隐藏）
        runCatching { if (TvCompat.isTv(this)) b.player.setDismissControlTime(Int.MAX_VALUE) }

        vodId = intent.getIntExtra("id", -1)
        sid = intent.getIntExtra("sid", -1)
        nid = intent.getIntExtra("nid", -1)
        title = intent.getStringExtra("title").orEmpty()
        srcKey = intent.getStringExtra("src") ?: com.zongyi.box.data.Src.JUZI.key

        // 各 setup 相互独立：任一失败仅降级对应功能，不影响取流播放
        runCatching { setupTopBar() }
        runCatching { setupSidePanel() }
        runCatching { setupPlayerCallbacks() }
        play()
    }

    private fun setupTopBar() {
        val p = b.player
        p.findViewById<android.widget.ImageView>(R.id.back).setOnClickListener { finish() }
        // surface_container 全屏铺底，若可聚焦会抢走遥控器焦点搜索（波束算法下
        // 全屏视图总是赢过同排小按钮）；遥控器按键由 Activity 层 dispatchKeyEvent 统一处理
        p.findViewById<View>(R.id.surface_container)?.isFocusable = false
        // SeekBar 构造函数默认 setFocusable(true)，会把遥控器左右焦点吞进进度条
        // （且无焦点高亮）；快进快退统一由 Activity 层方向键处理
        p.findViewById<View>(R.id.progress)?.isFocusable = false
        // 返回键不可聚焦：开播瞬间 GSY 隐藏 thumb 时系统会把焦点重分配给首个
        // 可聚焦可见视图（顶栏返回键），此后遥控器确认键会误触"返回"退出播放器；
        // 退出统一由 Activity 层 BACK 键处理
        p.findViewById<View>(R.id.back)?.isFocusable = false
        p.findViewById<android.widget.ImageView>(R.id.player_btn_speed).setOnClickListener { showSpeedSheet() }
        p.findViewById<android.widget.ImageView>(R.id.player_btn_prev).setOnClickListener { gotoEpisode(-1) }
        p.findViewById<android.widget.ImageView>(R.id.player_btn_next).setOnClickListener { gotoEpisode(1) }
        p.findViewById<android.widget.ImageView>(R.id.player_btn_line).setOnClickListener { showLineSheet() }
        p.findViewById<android.widget.ImageView>(R.id.player_btn_episodes).setOnClickListener { showEpisodeSheet() }
        playPauseBtn = p.findViewById(R.id.player_btn_playpause)
        playPauseBtn?.setOnClickListener { togglePlayPause() }
        // 初始焦点与"thumb 被隐藏后的焦点重分配"均落在布局首位的 focus_sink
        // （1px 常驻可聚焦视图），确保确认键始终由 Activity 层统一处理，
        // 不会误触返回键/线路按钮
        // 标题走 GSY 内置顶栏（随控制栏一起显隐）
        p.findViewById<android.widget.TextView>(R.id.title).text = title
        updatePlayPauseIcon()
        b.tvError.setOnClickListener {
            b.tvError.isVisible = false
            retryCount = 0
            retryPending = false
            play()
        }
    }

    /** 底栏播放/暂停按钮（状态跟随播放器，回调 + 轮询双保险同步图标） */
    private var playPauseBtn: android.widget.ImageView? = null

    /** 底栏播放/暂停按钮（状态跟随播放器，回调 + 轮询双保险同步图标） */
    private fun togglePlayPause() {
        val p = b.player
        if (p.currentState == com.shuyu.gsyvideoplayer.video.base.GSYVideoView.CURRENT_STATE_PLAYING) {
            p.onVideoPause()
        } else if (p.currentState == com.shuyu.gsyvideoplayer.video.base.GSYVideoView.CURRENT_STATE_PAUSE) {
            p.onVideoResume()
        } else if (p.currentState ==
            com.shuyu.gsyvideoplayer.video.base.GSYVideoView.CURRENT_STATE_AUTO_COMPLETE
        ) {
            // 播完停在末帧：遥控器按确认键从头重播
            p.startPlayLogic()
        }
        updatePlayPauseIcon()
    }

    private fun updatePlayPauseIcon() {
        if (playPauseBtn == null) playPauseBtn = b.player.findViewById(R.id.player_btn_playpause)
        val playing = b.player.currentState ==
            com.shuyu.gsyvideoplayer.video.base.GSYVideoView.CURRENT_STATE_PLAYING
        playPauseBtn?.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    /** GSY 播放回调：错误自动重试 */
    private fun setupPlayerCallbacks() {
        b.player.setVideoAllCallBack(object : VideoAllCallBack {
            override fun onStartPrepared(s: String?, vararg objects: Any?) {}
            override fun onPrepared(s: String?, vararg objects: Any?) {
                android.util.Log.i("ZongYiBox", "onPrepared s=$s current=$currentUrl")
                retryCount = 0
                retryPending = false
                updatePlayPauseIcon()
            }

            override fun onClickStartIcon(s: String?, vararg objects: Any?) {}
            override fun onClickStartError(s: String?, vararg objects: Any?) {}
            override fun onClickStop(s: String?, vararg objects: Any?) {
                updatePlayPauseIcon()
            }
            override fun onClickStopFullscreen(s: String?, vararg objects: Any?) {}
            override fun onClickResume(s: String?, vararg objects: Any?) {
                updatePlayPauseIcon()
            }
            override fun onClickResumeFullscreen(s: String?, vararg objects: Any?) {}
            override fun onClickSeekbar(s: String?, vararg objects: Any?) {}
            override fun onClickSeekbarFullscreen(s: String?, vararg objects: Any?) {}
            override fun onAutoComplete(s: String?, vararg objects: Any?) {
                // 自然播完：GSY 在 ExoPlayer STATE_ENDED（正常播完）时回调的是
                // onAutoComplete 而非 onComplete（onComplete 只在手动停止等场景触发）
                if (s == null || s != currentUrl) {
                    android.util.Log.i("ZongYiBox", "onAutoComplete(忽略:非当前集) s=$s current=$currentUrl")
                    return
                }
                // 二次过滤：切集时旧播放器释放可能产生伪回调，
                // 只有位置接近片尾（或位置读数无效）才自动连播
                val pos = b.player.currentPositionWhenPlaying
                val dur = b.player.duration
                if (dur > 0 && pos > 0 && pos < dur - 60_000) {
                    android.util.Log.i("ZongYiBox", "onAutoComplete(忽略:未真实播完) pos=$pos dur=$dur")
                    return
                }
                android.util.Log.i("ZongYiBox", "onAutoComplete(生效) pos=$pos dur=$dur")
                saveHistory()
                // 追更场景：本集播完自动连播下一集
                uiHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    gotoEpisode(1, silent = true)
                }
            }
            override fun onComplete(s: String?, vararg objects: Any?) {
                // 自然播完走 onAutoComplete（见上），此处不再处理连播
            }

            override fun onEnterFullscreen(s: String?, vararg objects: Any?) {}
            override fun onQuitFullscreen(s: String?, vararg objects: Any?) {}
            override fun onQuitSmallWidget(s: String?, vararg objects: Any?) {}
            override fun onEnterSmallWidget(s: String?, vararg objects: Any?) {}
            override fun onTouchScreenSeekVolume(s: String?, vararg objects: Any?) {}
            override fun onTouchScreenSeekPosition(s: String?, vararg objects: Any?) {}
            override fun onTouchScreenSeekLight(s: String?, vararg objects: Any?) {}
            override fun onPlayError(s: String?, vararg objects: Any?) {
                // 同 onComplete：忽略旧播放器释放时产生的伪错误回调
                if (s == null || s != currentUrl) return
                autoRetry()
            }

            override fun onClickStartThumb(s: String?, vararg objects: Any?) {}
            override fun onClickBlank(s: String?, vararg objects: Any?) {}
            override fun onClickBlankFullscreen(s: String?, vararg objects: Any?) {}
        })
    }

    /** 播放错误：自动重试（递增间隔 1.5s/3s/4.5s），失败 3 次后提示手动操作 */
    private fun autoRetry() {
        if (retryPending) return
        if (retryCount < MAX_RETRY) {
            retryPending = true
            retryCount++
            Toast.makeText(this, "网络波动，自动重试 $retryCount/$MAX_RETRY…", Toast.LENGTH_SHORT).show()
            uiHandler.postDelayed({
                retryPending = false
                if (isFinishing || isDestroyed) return@postDelayed
                b.player.setSeekOnStart(b.player.currentPositionWhenPlaying)
                b.player.startPlayLogic()
            }, retryCount * 1500L)
        } else {
            Toast.makeText(this, "播放失败，已自动重试 $MAX_RETRY 次\n可点击重试或切换线路", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- 取流与播放 ----------

    private fun play() {
        b.tvError.isVisible = false
        retryCount = 0
        retryPending = false
        lifecycleScope.launch {
            runSuspendCatching {
                // detail 兜底请求走新鲜缓存：从详情页点进来时通常已缓存，跳过网络
                val d = async {
                    detail ?: SiteApi.detail(
                        com.zongyi.box.data.Src.of(srcKey), vodId, SiteApi.DETAIL_TTL_MS
                    ).also { detail = it }
                }
                val stream = async { SiteApi.stream(com.zongyi.box.data.Src.of(srcKey), vodId, sid, nid) }
                d.await() to stream.await()
            }.onSuccess { (d, stream) ->
                // 播放前预取并过滤 m3u8 中的广告分片（博彩等），失败时自动回落原始地址
                // （详情页已预取过时，此处直接命中缓存，点击即播）
                val prepared = runSuspendCatching { SiteApi.prepareStream(stream.url, cacheDir) }
                    .getOrDefault(com.zongyi.box.data.PreparedStream(stream.url, 0))
                if (prepared.removedAdSegments > 0) {
                    android.util.Log.i("ZongYiBox", "已过滤 ${prepared.removedAdSegments} 个广告分片")
                    Toast.makeText(this@PlayerActivity, "已自动过滤 ${prepared.removedAdSegments} 段广告", Toast.LENGTH_SHORT).show()
                }
                startStream(prepared.url, stream.from)
                switching = false
                // 开播即预取下一集：手动切集/自动连播秒开
                nextEpisode()?.let { next ->
                    launch {
                        SiteApi.prefetchStream(com.zongyi.box.data.Src.of(srcKey), vodId, sid, next.nid, cacheDir)
                    }
                }
            }.onFailure { e ->
                switching = false
                if (e is CancellationException) return@onFailure
                b.tvError.isVisible = true
                b.tvError.text = "获取视频地址失败：${e.message}\n点击重试，或切换线路"
            }
        }
    }

    /** 下一集（当前线路）；无则返回 null */
    private fun nextEpisode(): Episode? {
        val src = detail?.source(sid) ?: return null
        val idx = src.episodes.indexOfFirst { it.nid == nid }
        return src.episodes.getOrNull(idx + 1)
    }

    @SuppressLint("SetTextI18n")
    private fun startStream(url: String, from: String) {
        val p: StandardGSYVideoPlayer = b.player
        // 请求头伪装成浏览器；站点 m3u8 无 Referer 校验，直接播放
        p.setUp(url, false, null as java.io.File?, BrowserHeaders.media(), title)
        currentUrl = url
        android.util.Log.i("ZongYiBox", "startStream url=$url")
        // 断点续播：首次进入时定位到上次观看位置
        p.setSeekOnStart(if (!resumed) resumePos() else 0)
        p.setSpeed(speeds[speedIndex])
        resetProgressUi()
        p.startPlayLogic()
        // TV：开播即显示控制栏并启动自动隐藏计时（暂停时保持显示）
        if (isTvDevice) showControlsTemporarily()
        resumed = true
        saveHistory()
        startProgressPolling()
    }

    /**
     * 切集/换线路/重试时显式清零进度 UI。
     * GSY 在新流 PREPAREING 期间不会主动刷新进度条，且其内部
     * getCurrentPositionWhenPlaying() 存在旧 mCurrentPosition 回退逻辑，
     * 不清零会残留上一集的进度条定位与时间文本。
     */
    @SuppressLint("SetTextI18n")
    private fun resetProgressUi() {
        val p = b.player
        p.findViewById<android.widget.SeekBar>(R.id.progress)?.apply {
            progress = 0
            secondaryProgress = 0
        }
        p.findViewById<android.widget.TextView>(R.id.current)?.text = "00:00"
        p.findViewById<android.widget.TextView>(R.id.total)?.text = "00:00"
        p.findViewById<android.widget.ProgressBar>(R.id.bottom_progressbar)?.progress = 0
    }

    /** 上次观看位置（毫秒，同来源同线路同集） */
    private fun resumePos(): Long {
        val h = Store.history(this).firstOrNull {
            it.vodId == vodId && it.srcKey == srcKey && it.sid == sid && it.nid == nid
        }
        // 接近片尾的不续播
        return if (h != null && h.durationMs > 0 && h.positionMs < h.durationMs - 15_000) h.positionMs else 0
    }

    /** 每 3 秒落盘观看进度 */
    private fun startProgressPolling() {
        pollTask?.let { uiHandler.removeCallbacks(it) }
        val task = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                saveHistory()
                updatePlayPauseIcon()
                // TV：GSY 每次缓冲恢复都会重新显示中央按钮（其隐藏依赖已被
                // 禁用的 dismissControlTask），轮询中持续纠正
                syncCenterButton()
                uiHandler.postDelayed(this, 3_000)
            }
        }
        pollTask = task
        uiHandler.postDelayed(task, 3_000)
    }

    private fun stopProgressPolling() {
        pollTask?.let { uiHandler.removeCallbacks(it) }
        pollTask = null
    }

    // ---------- 历史 ----------

    private fun saveHistory() {
        val p = b.player
        val d = detail
        val epName = d?.source(sid)?.episodes?.firstOrNull { it.nid == nid }?.name
            ?: intent.getStringExtra("episodeName").orEmpty()
        if (p.currentPositionWhenPlaying > 5_000) {
            Store.saveHistory(
                this,
                HistoryEntry(
                    vodId = vodId,
                    title = d?.title ?: title,
                    cover = d?.cover ?: intent.getStringExtra("cover").orEmpty(),
                    sid = sid, nid = nid,
                    episodeName = epName,
                    positionMs = p.currentPositionWhenPlaying,
                    durationMs = p.duration.takeIf { it > 0 } ?: 0L,
                    updatedAt = System.currentTimeMillis(),
                    src = srcKey
                )
            )
        }
    }

    // ---------- 选集 / 上下集 ----------

    private fun currentSource() = detail?.source(sid) ?: detail?.firstAvailable()

    private fun switchTo(ep: Episode) {
        // 防重入：上一集的切换还没完成（异步取流中）时，忽略新的切集请求，
        // 防止连播回调与手动切换互相触发形成"一直跳下去"的连锁
        if (switching) return
        switching = true
        // 先把当前集的进度落到历史（此时 nid 还是旧集），再切换
        saveHistory()
        nid = ep.nid
        resumed = false
        android.util.Log.i("ZongYiBox", "切集: sid=$sid nid=$nid ${ep.name}")
        play()
        Toast.makeText(this, "正在播放：${ep.name}", Toast.LENGTH_SHORT).show()
    }

    private fun gotoEpisode(offset: Int, silent: Boolean = false) {
        val src = currentSource() ?: return
        val idx = src.episodes.indexOfFirst { it.nid == nid }
        val target = src.episodes.getOrNull(idx + offset) ?: run {
            if (!silent) Toast.makeText(this, if (offset > 0) "已经是最后一集" else "已经是第一集", Toast.LENGTH_SHORT).show()
            return
        }
        switchTo(target)
    }

    private fun showEpisodeSheet() {
        val src = currentSource() ?: return
        showSidePanel("选集 · 共 ${src.episodes.size} 集") { list ->
            var selected: View? = null
            src.episodes.forEach { ep ->
                val row = panelRow(ep.name, ep.nid == nid) {
                    hideSidePanel()
                    switchTo(ep)
                }
                if (ep.nid == nid) selected = row
                list.addView(row)
            }
            selected
        }
    }

    // ---------- 线路 / 倍速 ----------

    private fun showLineSheet() {
        val d = detail ?: return
        showSidePanel("切换线路 · 共 ${d.sources.size} 条") { list ->
            var selected: View? = null
            d.sources.forEach { src ->
                val isCurrent = src.sid == sid
                val row = panelRow("${src.name} · ${src.episodes.size}集", isCurrent) {
                    hideSidePanel()
                    val ep = src.episodes.firstOrNull { it.nid == nid }
                        ?: src.episodes.firstOrNull()
                        ?: return@panelRow
                    sid = src.sid
                    switchTo(ep)
                }
                if (isCurrent) selected = row
                list.addView(row)
            }
            selected
        }
    }

    /** 倍速切换：与切换线路相同的右侧侧边栏面板交互 */
    private fun showSpeedSheet() {
        showSidePanel("倍速播放") { list ->
            var selected: View? = null
            speeds.forEachIndexed { i, s ->
                val label = if (s == 1.0f) "1.0x 常速" else "${s}x"
                val row = panelRow(label, i == speedIndex) {
                    hideSidePanel()
                    speedIndex = i
                    b.player.setSpeed(s)
                    Toast.makeText(this, "已切换为 ${label}", Toast.LENGTH_SHORT).show()
                }
                if (i == speedIndex) selected = row
                list.addView(row)
            }
            selected
        }
    }

    // ---------- 右侧侧边栏（B 站风格选集/线路面板） ----------

    /** 侧边栏面板宽度（dp），与 activity_player.xml 中 panelSide 一致 */
    private val sidePanelWidthDp = 300

    /** 初始化侧边栏交互：遮罩点击/关闭按钮收起面板 */
    private fun setupSidePanel() {
        b.panelRoot.setOnClickListener { hideSidePanel() }
        b.panelClose.setOnClickListener { hideSidePanel() }
    }

    /**
     * 从右侧滑入面板（B 站播放器风格）：
     * 遮罩渐显 + 面板平移入场，返回当前选中行用于自动滚动定位。
     */
    private fun showSidePanel(title: String, buildRows: (android.widget.LinearLayout) -> View?) {
        b.panelTitle.text = title
        b.panelList.removeAllViews()
        val selected = buildRows(b.panelList)
        val density = resources.displayMetrics.density
        b.panelSide.translationX = sidePanelWidthDp * density
        b.panelRoot.isVisible = true
        b.panelRoot.alpha = 0f
        b.panelRoot.animate().alpha(1f).setDuration(160).start()
        b.panelSide.animate().translationX(0f).setDuration(220).start()
        selected?.let { sel ->
            b.panelScroll.post { b.panelScroll.scrollTo(0, (sel.top - 140).coerceAtLeast(0)) }
            // TV：把遥控器焦点落到当前选中行，上下键直接在面板内导航
            sel.post { sel.requestFocus() }
        }
    }

    private fun hideSidePanel() {
        if (!b.panelRoot.isVisible) return
        val density = resources.displayMetrics.density
        b.panelSide.animate().translationX(sidePanelWidthDp * density).setDuration(200)
            .withEndAction { b.panelRoot.isVisible = false }
            .start()
    }

    /** 面板行（B 站风格）：圆角卡片行，当前项橙色加粗 + 橙色淡底，其余白字带涟漪反馈 */
    private fun panelRow(text: String, selected: Boolean, onClick: () -> Unit): View {
        val dp = resources.displayMetrics.density
        return android.widget.TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(getColor(if (selected) R.color.accent else R.color.text_primary))
            if (selected) paint.isFakeBoldText = true
            setPadding((22 * dp).toInt(), (26 * dp).toInt(), (22 * dp).toInt(), (26 * dp).toInt())
            if (selected) {
                setBackgroundResource(R.drawable.bg_panel_row_selected)
            } else {
                setBackgroundResource(R.drawable.bg_panel_row)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins((10 * dp).toInt(), (2 * dp).toInt(), (10 * dp).toInt(), (2 * dp).toInt()) }
        }
    }

    // ---------- 生命周期 ----------

    override fun onPause() {
        super.onPause()
        saveHistory()
        GSYVideoManager.onPause()
    }

    override fun onResume() {
        super.onResume()
        GSYVideoManager.onResume()
    }

    override fun onStop() {
        super.onStop()
        saveHistory()
    }

    // ---------- 遥控器 / 键盘（TV 适配） ----------

    /** 播放器控制栏内可聚焦的按钮 id 集合 */
    private val controlIds = setOf(
        R.id.back, R.id.player_btn_line,
        R.id.player_btn_playpause, R.id.player_btn_speed,
        R.id.player_btn_episodes, R.id.player_btn_prev, R.id.player_btn_next
    )

    private fun isControlFocused(): Boolean = currentFocus?.id in controlIds

    private fun controlsVisible(): Boolean {
        val top = b.player.findViewById<View>(R.id.layout_top) ?: return false
        val bottom = b.player.findViewById<View>(R.id.layout_bottom) ?: return false
        return top.isVisible && bottom.isVisible
    }

    /**
     * 遥控器按键统一在 Activity 层先行拦截：
     * 焦点可能落在 GSY 的 surface_container（可点击 View）上，它会把
     * 确认键消费成"点击切换控制栏显隐"、把方向键吞掉，导致 onKeyDown
     * 收不到事件。这里在事件进入视图层级前先交给 onKeyDown 处理。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            !b.panelRoot.isVisible && !isControlFocused()
        ) {
            val seek = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> true
                else -> false
            }
            if (seek || event.repeatCount == 0) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND,
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN ->
                        return onKeyDown(event.keyCode, event)
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * TV 遥控器键控：
     * - BACK：侧栏打开时只收起侧栏，否则退出播放
     * - 确认键 / 空格 / 媒体键：播放 / 暂停（焦点在控制按钮上时交给按钮）
     * - 左右方向键：快退 / 快进 10 秒（焦点在控制按钮上时是焦点移动）
     * - 下方向键：唤出控制栏并把焦点落到播放/暂停按钮，可在按钮间导航
     * - 上方向键：仅唤出控制栏预览（4 秒后自动隐藏）
     * - 侧栏打开时方向键交给焦点系统（选集/线路面板内移动）
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && b.panelRoot.isVisible) {
            hideSidePanel()
            return true
        }
        // 侧栏打开：方向键/确认键交给面板焦点系统
        if (b.panelRoot.isVisible) return super.onKeyDown(keyCode, event)
        // 焦点在控制按钮上：方向键/确认键交给焦点系统（按钮点击/焦点移动）
        if (isControlFocused()) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                togglePlayPause()
                showControlsTemporarily()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekBy(-10_000)
                showControlsTemporarily()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekBy(10_000)
                showControlsTemporarily()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                showControlsTemporarily()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!controlsVisible()) {
                    showControlsTemporarily()
                    // post 确保在 layout_bottom 可见并完成布局后再请求焦点，
                    // 否则焦点请求会被同帧布局吞掉（焦点仍留在 surface 上）
                    b.player.findViewById<View>(R.id.player_btn_playpause)?.also { btn ->
                        btn.post { btn.requestFocus() }
                    }
                    return true
                }
                return super.onKeyDown(keyCode, event)
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** 快进/快退（仅在播放或暂停态生效），并同步 GSY 进度条 */
    private fun seekBy(deltaMs: Long) {
        val p = b.player
        val active = p.currentState == com.shuyu.gsyvideoplayer.video.base.GSYVideoView.CURRENT_STATE_PLAYING ||
            p.currentState == com.shuyu.gsyvideoplayer.video.base.GSYVideoView.CURRENT_STATE_PAUSE
        if (!active) return
        val dur = p.duration
        if (dur <= 0) return
        val target = (p.currentPositionWhenPlaying + deltaMs).coerceIn(0L, dur)
        p.seekTo(target)
    }

    /** 控制栏自动隐藏任务（遥控器/键盘唤出用，4 秒后收起） */
    private var controlsHideTask: Runnable? = null

    /** 当前设备是否为 TV/盒子（TV 上 GSY 自动隐藏已禁用，控制栏显隐由本方法统一管理） */
    private val isTvDevice by lazy { TvCompat.isTv(this) }

    /**
     * 唤出顶栏/底栏控制栏，4 秒无操作自动隐藏。
     * 隐藏条件：正在播放 + 无侧栏打开 + 焦点不在控制按钮上；
     * 暂停/缓冲时保持显示（TV 常见行为），条件满足后 2 秒复查。
     */
    /** TV：中央播放/暂停大按钮纳入统一显隐管理（暂停/播完显示，播放中隐藏） */
    private fun syncCenterButton() {
        if (!isTvDevice) return
        val center = b.player.findViewById<View>(R.id.start) ?: return
        val state = b.player.currentState
        center.isVisible = state ==
            com.shuyu.gsyvideoplayer.video.base.GSYVideoView.CURRENT_STATE_PAUSE ||
            state == com.shuyu.gsyvideoplayer.video.base.GSYVideoView.CURRENT_STATE_AUTO_COMPLETE
    }

    private fun showControlsTemporarily() {
        cancelGsyDismissTimer()
        val top = b.player.findViewById<View>(R.id.layout_top) ?: return
        val bottom = b.player.findViewById<View>(R.id.layout_bottom) ?: return
        top.isVisible = true
        bottom.isVisible = true
        // TV：中央按钮随控制栏显隐（GSY 状态变化时会把中央按钮显示出来，
        // 而其隐藏依赖 dismissControlTask——TV 上已被禁用，会永久残留）
        syncCenterButton()
        controlsHideTask?.let { uiHandler.removeCallbacks(it) }
        val task = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                // 侧栏打开 / 焦点停在控制按钮上（遥控器导航中）：延迟复查，暂不隐藏
                if (b.panelRoot.isVisible || isControlFocused()) {
                    uiHandler.postDelayed(this, 2_000)
                    return
                }
                // 仅播放中自动隐藏：暂停/缓冲时保持显示
                val playing = b.player.currentState ==
                    com.shuyu.gsyvideoplayer.video.base.GSYVideoView.CURRENT_STATE_PLAYING
                if (!playing) {
                    uiHandler.postDelayed(this, 2_000)
                    return
                }
                top.isVisible = false
                bottom.isVisible = false
                // TV：中央大按钮随控制栏一起收起（避免播放中残留暂停/播放图标）
                if (isTvDevice) {
                    b.player.findViewById<View>(R.id.start)?.isVisible = false
                }
                // 保险：焦点若还在将隐藏的控制按钮上，交回焦点沉底视图
                if (isControlFocused()) {
                    b.player.findViewById<View>(R.id.focus_sink)?.requestFocus()
                }
            }
        }
        controlsHideTask = task
        uiHandler.postDelayed(task, 4_000)
    }

    /**
     * 取消 GSY 内部待触发的自动隐藏计时（cancelDismissControlViewTimer 为 protected，
     * 用反射调用）。GSY 的 dismissControlTask 会在触摸/点击/状态变化时被反复调度，
     * 若不取消，它可能在我们手动唤出控件后 1~2 秒内触发 hideAllWidget()，
     * 造成"刚唤出就消失"的竞态。TV 上 GSY 自动隐藏已整体禁用，此处为触屏端兜底。
     */
    private fun cancelGsyDismissTimer() {
        runCatching {
            b.player.javaClass
                .getSuperclassRecursive("cancelDismissControlViewTimer")
                ?.let { m ->
                    m.isAccessible = true
                    m.invoke(b.player)
                }
        }
    }

    private fun Class<*>.getSuperclassRecursive(methodName: String): java.lang.reflect.Method? {
        var c: Class<*>? = this
        while (c != null) {
            try {
                return c.getDeclaredMethod(methodName)
            } catch (_: NoSuchMethodException) {
                c = c.superclass
            } catch (_: SecurityException) {
                return null
            }
        }
        return null
    }

    override fun onDestroy() {
        controlsHideTask?.let { uiHandler.removeCallbacks(it) }
        stopProgressPolling()
        GSYVideoManager.releaseAllVideos()
        super.onDestroy()
    }
}
