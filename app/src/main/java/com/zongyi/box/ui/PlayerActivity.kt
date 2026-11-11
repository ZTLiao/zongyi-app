package com.zongyi.box.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
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

    /** 退出播放器时展示一次插屏广告（未配置 Zone ID 时静默跳过） */
    private var adShownOnExit = false

    override fun finish() {
        if (!adShownOnExit) {
            adShownOnExit = true
            com.zongyi.box.ads.AdManager.showInterstitial(this)
        }
        super.finish()
    }

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
        PlayerFactory.setPlayManager(Exo2PlayerManager::class.java)

        // 页面本身已是全屏横屏，隐藏 GSY 自带全屏按钮，
        // 避免触发 startWindowFullscreen 跳转未配置的 GSYFullActivity 而崩溃
        b.player.getFullscreenButton().visibility = View.GONE
        // 关闭"当前为移动网络"提示弹窗，避免播放被弹窗打断
        b.player.setNeedShowWifiTip(false)

        vodId = intent.getIntExtra("id", -1)
        sid = intent.getIntExtra("sid", -1)
        nid = intent.getIntExtra("nid", -1)
        title = intent.getStringExtra("title").orEmpty()
        srcKey = intent.getStringExtra("src") ?: com.zongyi.box.data.Src.JUZI.key
        b.tvTitle.text = title

        setupTopBar()
        setupPlayerCallbacks()
        play()
    }

    private fun setupTopBar() {
        b.btnBack.setOnClickListener { finish() }
        b.btnSpeed.setOnClickListener { showSpeedDialog() }
        b.btnPrev.setOnClickListener { gotoEpisode(-1) }
        b.btnNext.setOnClickListener { gotoEpisode(1) }
        b.btnLine.setOnClickListener { showLineSheet() }
        b.btnEpisodes.setOnClickListener { showEpisodeSheet() }
        b.btnQuality.setOnClickListener {
            Toast.makeText(this, "当前 HLS 流为自适应码率，清晰度自动匹配", Toast.LENGTH_SHORT).show()
        }
        b.tvError.setOnClickListener {
            b.tvError.isVisible = false
            retryCount = 0
            retryPending = false
            play()
        }
        // 顶栏倍速按钮常显当前倍速
        b.btnSpeed.text = "倍速 ${speeds[speedIndex]}x"
    }

    /** GSY 播放回调：错误自动重试 */
    private fun setupPlayerCallbacks() {
        b.player.setVideoAllCallBack(object : VideoAllCallBack {
            override fun onStartPrepared(s: String?, vararg objects: Any?) {}
            override fun onPrepared(s: String?, vararg objects: Any?) {
                android.util.Log.i("ZongYiBox", "onPrepared s=$s current=$currentUrl")
                retryCount = 0
                retryPending = false
            }

            override fun onClickStartIcon(s: String?, vararg objects: Any?) {}
            override fun onClickStartError(s: String?, vararg objects: Any?) {}
            override fun onClickStop(s: String?, vararg objects: Any?) {}
            override fun onClickStopFullscreen(s: String?, vararg objects: Any?) {}
            override fun onClickResume(s: String?, vararg objects: Any?) {}
            override fun onClickResumeFullscreen(s: String?, vararg objects: Any?) {}
            override fun onClickSeekbar(s: String?, vararg objects: Any?) {}
            override fun onClickSeekbarFullscreen(s: String?, vararg objects: Any?) {}
            override fun onAutoComplete(s: String?, vararg objects: Any?) {}
            override fun onComplete(s: String?, vararg objects: Any?) {
                // 只有"当前正在播放的这一集"自然播完才自动连播。
                // 切换集数时旧播放器被释放/中断也会触发本回调（s 是旧集 URL），
                // 不加过滤会造成"切一集就连锁跳到底"的 bug。
                if (s == null || s != currentUrl) {
                    android.util.Log.i("ZongYiBox", "onComplete(忽略:非当前集) s=$s current=$currentUrl")
                    return
                }
                // 二次过滤：切集时 GSY 在 startPlayLogic 后会立刻回一次伪完成回调
                // （pos=0 / dur=新集时长），只有真实播完（pos 接近 dur）才自动连播
                val pos = b.player.currentPositionWhenPlaying
                val dur = b.player.duration
                if (pos <= 0 || dur <= 0 || pos < dur - 60_000) {
                    android.util.Log.i("ZongYiBox", "onComplete(忽略:未真实播完) pos=$pos dur=$dur")
                    return
                }
                android.util.Log.i("ZongYiBox", "onComplete(生效) pos=$pos dur=$dur")
                saveHistory()
                // 追更场景：本集播完自动连播下一集
                uiHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    gotoEpisode(1, silent = true)
                }
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
                val d = async { detail ?: SiteApi.detail(com.zongyi.box.data.Src.of(srcKey), vodId).also { detail = it } }
                val stream = async { SiteApi.stream(com.zongyi.box.data.Src.of(srcKey), vodId, sid, nid) }
                d.await() to stream.await()
            }.onSuccess { (d, stream) ->
                // 播放前预取并过滤 m3u8 中的广告分片（博彩等），失败时自动回落原始地址
                val prepared = runSuspendCatching { SiteApi.prepareStream(stream.url, cacheDir) }
                    .getOrDefault(com.zongyi.box.data.PreparedStream(stream.url, 0))
                if (prepared.removedAdSegments > 0) {
                    android.util.Log.i("ZongYiBox", "已过滤 ${prepared.removedAdSegments} 个广告分片")
                    Toast.makeText(this@PlayerActivity, "已自动过滤 ${prepared.removedAdSegments} 段广告", Toast.LENGTH_SHORT).show()
                }
                startStream(prepared.url, stream.from)
                switching = false
            }.onFailure { e ->
                switching = false
                if (e is CancellationException) return@onFailure
                b.tvError.isVisible = true
                b.tvError.text = "获取视频地址失败：${e.message}\n点击重试，或切换线路"
            }
        }
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
        p.startPlayLogic()
        resumed = true
        saveHistory()
        startProgressPolling()
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
        val dialog = BottomSheetDialog(this)
        val group = ChipGroup(this).apply { chipSpacingHorizontal = 8 }
        src.episodes.forEach { ep ->
            val chip = Chip(this)
            chip.text = ep.name
            chip.isCheckable = true
            chip.isChecked = ep.nid == nid
            chip.chipBackgroundColor = androidx.core.content.ContextCompat.getColorStateList(this, R.color.chip_bg)
            chip.setTextColor(getColorStateList(R.color.chip_text))
            chip.setOnClickListener {
                dialog.dismiss()
                switchTo(ep)
            }
            group.addView(chip)
        }
        dialog.setContentView(wrapSheet(group, "选集 · 共 ${src.episodes.size} 集"))
        dialog.show()
    }

    // ---------- 线路 / 倍速 ----------

    private fun showLineSheet() {
        val d = detail ?: return
        val dialog = BottomSheetDialog(this)
        val group = ChipGroup(this)
        d.sources.forEach { src ->
            val chip = Chip(this)
            chip.text = "${src.name}（${src.episodes.size}集）"
            chip.isCheckable = true
            chip.isChecked = src.sid == sid
            chip.chipBackgroundColor = androidx.core.content.ContextCompat.getColorStateList(this, R.color.chip_bg)
            chip.setTextColor(getColorStateList(R.color.chip_text))
            chip.setOnClickListener {
                dialog.dismiss()
                val ep = src.episodes.firstOrNull { it.nid == nid }
                    ?: src.episodes.firstOrNull()
                    ?: return@setOnClickListener
                sid = src.sid
                switchTo(ep)
            }
            group.addView(chip)
        }
        val current = d.sources.firstOrNull { it.sid == sid }
        dialog.setContentView(wrapSheet(group, "切换线路 · 当前 ${current?.name ?: "未知"}"))
        dialog.show()
    }

    private fun showSpeedDialog() {
        val items = speeds.map { if (it == 1.0f) "1.0x 常速" else "${it}x" }.toTypedArray()
        AlertDialog.Builder(this, R.style.Theme_ZongYiBox)
            .setTitle("倍速播放")
            .setSingleChoiceItems(items, speedIndex) { dlg, which ->
                speedIndex = which
                b.player.setSpeed(speeds[which])
                b.btnSpeed.text = "倍速 ${speeds[which]}x"
                Toast.makeText(this, "已切换为 ${speeds[which]}x", Toast.LENGTH_SHORT).show()
                dlg.dismiss()
            }
            .show()
    }

    private fun wrapSheet(view: View, title: String): View {
        val container = androidx.core.widget.NestedScrollView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(getColor(R.color.surface))
            setPadding(48, 40, 48, 56)
        }
        // 标题行（当前集/当前线路），增强底部弹窗的上下文感
        val titleView = android.widget.TextView(this).apply {
            text = title
            setTextColor(getColor(R.color.text_secondary))
            textSize = 14f
            setPadding(4, 0, 4, 16)
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(titleView)
            addView(view)
        }
        container.addView(box, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return container
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

    override fun onDestroy() {
        stopProgressPolling()
        GSYVideoManager.releaseAllVideos()
        super.onDestroy()
    }
}
