package com.zongyi.box.ui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import coil.load
import com.shuyu.gsyvideoplayer.GSYVideoManager
import com.shuyu.gsyvideoplayer.listener.VideoAllCallBack
import com.shuyu.gsyvideoplayer.player.PlayerFactory
import com.shuyu.gsyvideoplayer.utils.GSYVideoType
import com.zongyi.box.R
import com.zongyi.box.data.BrowserHeaders
import com.zongyi.box.data.LiveApi
import com.zongyi.box.data.LiveLink
import com.zongyi.box.data.LiveMatch
import com.zongyi.box.data.LiveSite
import com.zongyi.box.data.ReplayItem
import com.zongyi.box.data.runSuspendCatching
import com.zongyi.box.databinding.ActivityLivePlayerBinding
import com.zongyi.box.databinding.ItemLiveLineBinding
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import tv.danmaku.ijk.media.exo2.Exo2PlayerManager

/**
 * 体育直播 / 录像播放页。
 *
 * 横屏「左画面 + 右信息栏」布局：
 * - 左：GSY + Media3 Exo，直接播放 m3u8（不加载第三方播放页/广告）
 * - 右：联赛、状态、对阵、全部信号线路；点线路即时切换
 * - 播放出错自动重试两次，仍失败则提示刷新线路（auth_key 会过期）
 */
class LivePlayerActivity : AppCompatActivity() {

    companion object {
        private const val MODE_LIVE = "live"
        private const val MODE_REPLAY = "replay"

        private const val EXTRA_MODE = "mode"
        private const val EXTRA_ID = "id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_LEAGUE = "league"
        private const val EXTRA_TIME = "time"
        private const val EXTRA_STATUS = "status"
        private const val EXTRA_HOME = "home"
        private const val EXTRA_AWAY = "away"
        private const val EXTRA_HOME_LOGO = "home_logo"
        private const val EXTRA_AWAY_LOGO = "away_logo"

        // ---- AI（AppFunctions）启动时附带的首选项 ----

        /** 初始倍速，如 1.5f */
        const val EXTRA_AI_SPEED = "ai_speed"

        /** 初始画面比例：fit / stretch / crop */
        const val EXTRA_AI_SCALE = "ai_scale"

        /** 初始是否显示右侧信息栏 */
        const val EXTRA_AI_SIDEBAR = "ai_sidebar"

        /** 初始是否启用底部控制条 */
        const val EXTRA_AI_CONTROLS = "ai_controls"

        /** 优先使用的线路名（如「线路C4」） */
        const val EXTRA_AI_LINE = "ai_line"

        fun liveIntent(ctx: Context, match: LiveMatch): Intent =
            Intent(ctx, LivePlayerActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_LIVE)
                putExtra(EXTRA_ID, match.id)
                putExtra(EXTRA_TITLE, "${match.home} vs ${match.away}")
                putExtra(EXTRA_LEAGUE, match.league)
                putExtra(EXTRA_TIME, match.time)
                putExtra(EXTRA_STATUS, match.status)
                putExtra(EXTRA_HOME, match.home)
                putExtra(EXTRA_AWAY, match.away)
                putExtra(EXTRA_HOME_LOGO, match.homeLogo)
                putExtra(EXTRA_AWAY_LOGO, match.awayLogo)
            }

        fun replayIntent(ctx: Context, item: ReplayItem): Intent =
            Intent(ctx, LivePlayerActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_REPLAY)
                putExtra(EXTRA_ID, item.id)
                putExtra(EXTRA_TITLE, item.title)
                putExtra(EXTRA_LEAGUE, "录像回放")
                putExtra(EXTRA_TIME, item.publishedAt)
            }

        fun startLive(ctx: Context, match: LiveMatch) = ctx.startActivity(liveIntent(ctx, match))

        fun startReplay(ctx: Context, item: ReplayItem) = ctx.startActivity(replayIntent(ctx, item))
    }

    private lateinit var b: ActivityLivePlayerBinding

    private var mode = MODE_LIVE
    private var id = -1
    private var title = ""

    private var links: List<LiveLink> = emptyList()
    private var currentLink: LiveLink? = null
    private var currentIndex = -1
    private val failedUrls = mutableSetOf<String>()
    private var loadingLinks = false

    /** AI 指定的优先线路名；线路加载完成后优先选择 */
    private var preferredLine: String? = null

    /** 全部线路播放失败后已自动刷新过一次线路（防死循环） */
    private var autoReloaded = false

    private val uiHandler = Handler(Looper.getMainLooper())

    private val lineRows = mutableListOf<LineRow>()

    /** 上次成功播放的线路（按 模式+赛事 记忆，下次进入优先使用） */
    private val linePrefs by lazy { getSharedPreferences("live_lines", MODE_PRIVATE) }

    private val lineKey: String get() = "${mode}_$id"

    private class LineRow(val bind: ItemLiveLineBinding)

    // ---------------- 播放设置（侧栏 / 控制条 / 倍速 / 画面比例） ----------------

    /** 播放设置记忆：倍速、画面比例、控制条开关 */
    private val playerPrefs by lazy { getSharedPreferences("live_player", MODE_PRIVATE) }

    private var currentSpeed = 1f
    private var currentScale = 0
    private var controlBarEnabled = true
    private var seeking = false

    private val hideControlBar = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        // 暂停或打开设置面板时保持控制条，方便操作
        if (!userPaused && !b.settingsPanel.isVisible) b.controlBar.isVisible = false
    }

    /** 单击画面延迟执行，双击时取消，避免双击顺带切换控制条 */
    private val singleTapRunnable = Runnable { onSingleTap() }

    private val progressTicker = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            updateProgress()
            uiHandler.postDelayed(this, 500L)
        }
    }

    // ---------------- 手势交互状态 ----------------

    private enum class GestureMode { NONE, SEEK, BRIGHTNESS, VOLUME }

    private var gestureMode = GestureMode.NONE
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var lastTapAt = 0L
    private var speedBoosted = false
    /** 用户手动暂停（直播流上 isInPlayingState 不一定可靠，自己记状态） */
    private var userPaused = false
    private var gestureHintShown = false
    private var liveSeekHintShown = false

    private var startBrightness = 0.5f
    private var startVolume = 0
    private var maxVolume = 1
    private var seekStart = 0L
    private var seekTarget = 0L

    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private val longPressRunnable = Runnable { startSpeedBoost() }

    private val hideOsd = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        b.gestureOsd.isVisible = false
    }

    private val siteSwitchListener: (String) -> Unit = { base ->
        runOnUiThread { showTip("已切换直播站点：${base.substringAfter("://")}") }
    }

    private val hideTip = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        b.tvTip.animate().alpha(0f).setDuration(300)
            .withEndAction { b.tvTip.isVisible = false }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLivePlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        runCatching { PlayerFactory.setPlayManager(Exo2PlayerManager::class.java) }
        runCatching { b.player.getFullscreenButton().visibility = View.GONE }
        runCatching { b.player.getBackButton().visibility = View.GONE }
        runCatching { b.player.getTitleTextView().visibility = View.GONE }
        runCatching { b.player.setNeedShowWifiTip(false) }
        // 手势由自绘手势层接管，关闭播放器自带的触摸控件（底部进度条/中央按钮）
        runCatching { b.player.setIsTouchWiget(false) }
        runCatching { b.player.setIsTouchWigetFull(false) }
        // GSY 在缓冲、尺寸变化时会再次弹出控件，把自动隐藏时间压到极短，几乎不可见
        runCatching { b.player.setDismissControlTime(200) }
        hideNativeControls()

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_LIVE
        id = intent.getIntExtra(EXTRA_ID, -1)
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        bindHeader()
        setupPlayerCallbacks()
        setupGestures()
        setupSidePanel()
        setupControlBar()
        applyAiExtras()
        setupSettingsPanel()
        applyModeUi()
        LivePlayerBridge.attach(this)

        b.btnBack.setOnClickListener { finish() }
        b.tvError.setOnClickListener {
            autoReloaded = false
            loadLinks(force = true)
        }
        b.btnRetry.setOnClickListener {
            autoReloaded = false
            loadLinks(force = true)
        }

        loadLinks(force = false)
    }

    private fun bindHeader() {
        b.tvTitle.text = title
        b.tvLeague.text = intent.getStringExtra(EXTRA_LEAGUE).orEmpty().ifBlank { "体育赛事" }
        b.tvTime.text = intent.getStringExtra(EXTRA_TIME).orEmpty()

        val status = intent.getStringExtra(EXTRA_STATUS).orEmpty()
        val isLive = mode == MODE_LIVE && status.contains("直播")
        b.tvStatus.text = when {
            isLive -> "LIVE"
            mode == MODE_REPLAY -> "回放"
            status.isNotBlank() -> status
            else -> "赛事"
        }
        if (isLive) {
            b.tvStatus.setBackgroundResource(R.drawable.bg_badge)
            b.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        } else {
            b.tvStatus.background = null
            b.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }

        val home = intent.getStringExtra(EXTRA_HOME).orEmpty()
        val away = intent.getStringExtra(EXTRA_AWAY).orEmpty()
        if (home.isBlank() && away.isBlank()) {
            b.teamRow.isVisible = false
        } else {
            b.tvHome.text = home
            b.tvAway.text = away
            bindLogo(b.ivHome, intent.getStringExtra(EXTRA_HOME_LOGO).orEmpty())
            bindLogo(b.ivAway, intent.getStringExtra(EXTRA_AWAY_LOGO).orEmpty())
        }
    }

    private fun bindLogo(view: ImageView, url: String) {
        if (url.isBlank()) view.setImageDrawable(null) else view.load(url) { crossfade(true) }
    }

    private fun setupPlayerCallbacks() {
        b.player.setVideoAllCallBack(object : VideoAllCallBack {
            override fun onStartPrepared(s: String?, vararg objects: Any?) {}
            override fun onPrepared(s: String?, vararg objects: Any?) {
                if (s == null || s != currentLink?.url) return
                b.loadingBox.isVisible = false
                b.tvError.isVisible = false
                autoReloaded = false
                hideNativeControls()
                // setUp 会重建渲染层，准备完成后恢复用户选择的倍速与画面比例
                runCatching { b.player.setSpeed(currentSpeed) }
                applyScale(currentScale)
                updatePlayPauseIcon()
                showControlBar(autoHide = true)
                // 记住真正可播的线路，下次进入同一场比赛优先使用
                linePrefs.edit().putString(lineKey, s).apply()
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
            override fun onComplete(s: String?, vararg objects: Any?) {}
            override fun onEnterFullscreen(s: String?, vararg objects: Any?) {}
            override fun onQuitFullscreen(s: String?, vararg objects: Any?) {}
            override fun onQuitSmallWidget(s: String?, vararg objects: Any?) {}
            override fun onEnterSmallWidget(s: String?, vararg objects: Any?) {}
            override fun onTouchScreenSeekVolume(s: String?, vararg objects: Any?) {}
            override fun onTouchScreenSeekPosition(s: String?, vararg objects: Any?) {}
            override fun onTouchScreenSeekLight(s: String?, vararg objects: Any?) {}

            override fun onPlayError(s: String?, vararg objects: Any?) {
                if (s == null || s != currentLink?.url) return
                if (!failedUrls.add(s)) return
                updateLineStates()
                // 某条线路失效（会停播/防盗链 403）时自动切到下一条，全部失败才提示
                val next = links.indices.firstOrNull { it > currentIndex && links[it].url !in failedUrls }
                    ?: links.indices.firstOrNull { links[it].url !in failedUrls }
                if (next != null) {
                    b.loadingBox.isVisible = true
                    showTip("线路 ${currentIndex + 1} 无响应，正在切换线路…")
                    uiHandler.postDelayed({
                        if (!isFinishing && !isDestroyed) play(links[next])
                    }, 1200L)
                } else {
                    b.loadingBox.isVisible = false
                    if (!autoReloaded) {
                        // 全部线路失效（站点换域名 / auth_key 过期）：自动刷新一次线路，
                        // 刷新会重新发现镜像并重取;仍失败才提示，避免死循环
                        autoReloaded = true
                        showTip("全部线路失效，正在重新查找线路…", 4000L)
                        loadLinks(force = true)
                    } else {
                        showError("暂无可用信号\n该场可能已结束，点击刷新重试")
                    }
                }
            }

            override fun onClickStartThumb(s: String?, vararg objects: Any?) {}
            override fun onClickBlank(s: String?, vararg objects: Any?) {}
            override fun onClickBlankFullscreen(s: String?, vararg objects: Any?) {}
        })
    }

    private fun loadLinks(force: Boolean) {
        if (loadingLinks || id <= 0) {
            if (id <= 0) showError("赛事信息缺失")
            return
        }
        loadingLinks = true
        b.loadingBox.isVisible = true
        b.tvLoadingTip.text = if (force) "正在重新查找线路…" else "正在获取信号线路…"
        b.tvError.isVisible = false

        lifecycleScope.launch {
            val result = runSuspendCatching {
                if (mode == MODE_REPLAY) {
                    LiveApi.replayDetail(id, ttlMs = if (force) 0 else 10 * 60_000L).links
                } else {
                    LiveApi.sources(id, ttlMs = if (force) 0 else 40_000L)
                }
            }
            loadingLinks = false
            if (isFinishing || isDestroyed) return@launch
            result.onSuccess { fetched ->
                b.tvLoadingTip.text = "正在检测可用线路…"
                // 站点线路短时有效：先并发探测，能出流的排在前面，避免默认播到已失效线路
                val ranking = runSuspendCatching { LiveApi.rankPlayable(fetched) }
                    .getOrDefault(LiveApi.PlayableRanking(fetched, -1))
                val list = ranking.links
                links = list
                failedUrls.clear()
                renderLines()
                // 优先 AI 指定的线路，其次上次成功过的线路，最后用探测可用的第一条
                val aiLine = preferredLine?.let { linkByName(it) }
                if (preferredLine != null && aiLine == null) {
                    showTip("未找到线路 $preferredLine，已使用默认线路", 2500L)
                }
                preferredLine = null
                val preferred = linePrefs.getString(lineKey, null)
                val pool = ranking.playable.ifEmpty { list }
                val first = aiLine
                    ?: pool.firstOrNull { it.url == preferred }
                    ?: pool.firstOrNull()
                if (first == null) {
                    b.loadingBox.isVisible = false
                    val notStarted = mode == MODE_LIVE &&
                        intent.getStringExtra(EXTRA_STATUS).orEmpty().contains("未开始")
                    showError(
                        if (notStarted) {
                            "比赛尚未开始\n暂无直播信号，稍后点击重试"
                        } else {
                            "该场次暂无可用信号\n站点尚未提供，点击重试"
                        }
                    )
                } else if (ranking.allRejected && aiLine == null) {
                    // 探测明确判定全部线路失效（多数为比赛已结束）：重取一次线路，
                    // 仍失效直接提示，不必逐条等待播放器失败
                    if (!autoReloaded) {
                        autoReloaded = true
                        showTip("线路暂不可用，正在重新查找…", 4000L)
                        loadLinks(force = true)
                    } else {
                        b.loadingBox.isVisible = false
                        showError("暂无可用信号\n该场可能已结束，点击刷新重试")
                    }
                } else {
                    play(first)
                }
            }.onFailure {
                b.loadingBox.isVisible = false
                renderLines()
                showError("线路加载失败\n站点可能正在切换，点击重试")
            }
        }
    }

    private fun renderLines() {
        b.lineList.removeAllViews()
        b.lineListPanel.removeAllViews()
        lineRows.clear()
        b.tvLineCount.text = if (links.isEmpty()) "" else "共 ${links.size} 条"

        if (links.isEmpty()) {
            val empty = "暂无可用信号，点击右上角刷新"
            listOf<LinearLayout>(b.lineList, b.lineListPanel).forEach { container ->
                container.addView(TextView(this).apply {
                    text = empty
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    textSize = 12f
                    setPadding(0, dp(8), 0, dp(8))
                })
            }
            return
        }

        links.forEach { link ->
            addLineRow(link, b.lineList)
            addLineRow(link, b.lineListPanel)
        }
        updateLineStates()

        if (TvCompat.isTv(this)) lineRows.firstOrNull()?.bind?.root?.requestFocus()
    }

    /** 侧栏与设置面板各生成一份线路行，状态统一由 updateLineStates() 刷新 */
    private fun addLineRow(link: LiveLink, container: LinearLayout) {
        val row = ItemLiveLineBinding.inflate(layoutInflater, container, false)
        row.tvLineName.text = link.name
        row.root.tag = link.url
        row.root.setOnClickListener { play(link) }
        container.addView(row.root)
        lineRows.add(LineRow(row))
    }

    /** 线路行状态：播放中 / 备用 / 失效（失败置灰，方便一眼看出哪条还能用） */
    private fun updateLineStates() {
        lineRows.forEach { row ->
            val url = row.bind.root.tag as? String
            val failed = url != null && url in failedUrls
            val playing = url != null && url == currentLink?.url
            row.bind.root.isSelected = playing
            row.bind.tvLineName.alpha = if (failed) 0.4f else 1f
            row.bind.tvLineState.text = when {
                failed -> "失效"
                playing -> "播放中"
                else -> "备用"
            }
            row.bind.tvLineState.setTextColor(
                ContextCompat.getColor(this, if (playing) R.color.accent else R.color.text_secondary)
            )
        }
    }

    private fun play(link: LiveLink) {
        currentLink = link
        currentIndex = links.indexOfFirst { it.url == link.url }
        failedUrls.remove(link.url)
        userPaused = false
        b.tvError.isVisible = false
        b.loadingBox.isVisible = true
        b.tvLoadingTip.text = "正在连接 ${link.name}…"
        updateLineStates()

        // 88player 包装线路（录像回放）落在 123kq 系 CDN 上，带 Referer 一律 403，
        // 实测必须完全不带 Referer 才能取到 m3u8 与分片
        val headers = if (link.referer.contains("88player.top", ignoreCase = true)) {
            BrowserHeaders.mediaNoRef
        } else {
            BrowserHeaders.media(link.referer.ifBlank { LiveSite.base + "/" })
        }
        runCatching {
            b.player.setUp(
                link.url,
                false,
                null as File?,
                headers,
                title,
            )
            b.player.startPlayLogic()
        }.onFailure {
            b.loadingBox.isVisible = false
            showError("播放器启动失败\n可切换线路")
        }
    }

    /** 顶部浮层提示，自动淡出 */
    private fun showTip(text: String, autoHideMs: Long = 2500L) {
        if (isFinishing || isDestroyed) return
        uiHandler.removeCallbacks(hideTip)
        b.tvTip.animate().cancel()
        b.tvTip.text = text
        b.tvTip.alpha = 1f
        b.tvTip.isVisible = true
        uiHandler.postDelayed(hideTip, autoHideMs)
    }

    private fun showError(text: String) {
        uiHandler.removeCallbacks(hideTip)
        b.tvTip.isVisible = false
        b.tvError.text = text
        b.tvError.isVisible = true
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ---------------- 侧栏 / 控制条 / 播放设置 ----------------

    /** 侧栏收起：画面自动铺满，右上角出现展开按钮 */
    private fun setupSidePanel() {
        b.btnCollapse.setOnClickListener { applySidePanelVisible(false) }
        b.btnExpand.setOnClickListener { applySidePanelVisible(true) }
    }

    private fun applySidePanelVisible(visible: Boolean) {
        b.settingsPanel.isVisible = false
        b.sidePanel.isVisible = visible
        b.btnExpand.isVisible = !visible
    }

    private fun applyModeUi() {
        val isReplay = mode == MODE_REPLAY
        b.seekBar.isVisible = isReplay
        b.tvCur.isVisible = isReplay
        b.tvDur.isVisible = isReplay
        b.tvLiveBadge.isVisible = !isReplay
        b.tvCur.text = if (isReplay) "00:00" else ""
        b.tvDur.text = if (isReplay) "--:--" else ""
    }

    private fun setupControlBar() {
        controlBarEnabled = playerPrefs.getBoolean("bar", true)
        currentSpeed = playerPrefs.getFloat("speed", 1f)
        currentScale = playerPrefs.getInt("scale", GSYVideoType.SCREEN_TYPE_DEFAULT)
        applyScale(currentScale)

        b.btnPlayPause.setOnClickListener {
            togglePlay()
            resetControlBarTimer()
        }

        b.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val duration = runCatching { b.player.duration }.getOrDefault(0L)
                if (duration > 0) b.tvCur.text = timeText(duration * progress / 1000)
                resetControlBarTimer()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                seeking = true
                uiHandler.removeCallbacks(hideControlBar)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                seeking = false
                val duration = runCatching { b.player.duration }.getOrDefault(0L)
                if (duration > 0) {
                    runCatching { b.player.seekTo(duration * seekBar.progress / 1000) }
                }
                resetControlBarTimer()
            }
        })

        b.controlBar.isVisible = false
    }

    private fun showControlBar(autoHide: Boolean = true) {
        if (!controlBarEnabled || isFinishing || isDestroyed) return
        uiHandler.removeCallbacks(hideControlBar)
        b.controlBar.isVisible = true
        if (autoHide) uiHandler.postDelayed(hideControlBar, 3000L)
    }

    private fun resetControlBarTimer() {
        if (!b.controlBar.isVisible || userPaused) return
        uiHandler.removeCallbacks(hideControlBar)
        uiHandler.postDelayed(hideControlBar, 3000L)
    }

    private fun updatePlayPauseIcon() {
        b.btnPlayPause.setImageResource(
            if (userPaused) R.drawable.ic_play else R.drawable.ic_pause
        )
    }

    /** 回放时刷新进度；直播没有可拖动进度，只显示 LIVE 标识 */
    private fun updateProgress() {
        if (mode != MODE_REPLAY) return
        val duration = runCatching { b.player.duration }.getOrDefault(0L)
        val position = runCatching { b.player.currentPositionWhenPlaying }.getOrDefault(0L)
        if (duration <= 0) return
        b.tvDur.text = timeText(duration)
        if (!seeking) {
            b.seekBar.progress = (position * 1000 / duration).toInt().coerceIn(0, 1000)
            b.tvCur.text = timeText(position)
        }
    }

    private fun setupSettingsPanel() {
        b.btnSettings.setOnClickListener {
            val show = !b.settingsPanel.isVisible
            b.settingsPanel.isVisible = show
            if (show) {
                uiHandler.removeCallbacks(hideControlBar)
                b.controlBar.isVisible = controlBarEnabled
            } else {
                resetControlBarTimer()
            }
        }

        setupSpeedChips()
        setupScaleChips()
        setupControlChips()
    }

    private fun setupSpeedChips() {
        b.speedChips.removeAllViews()
        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
            val chip = makeChip(speedLabel(speed), currentSpeed == speed) {
                applySpeed(speed)
            }
            chip.tag = speed
            b.speedChips.addView(chip)
        }
    }

    private fun setupScaleChips() {
        b.scaleChips.removeAllViews()
        listOf(
            Triple(GSYVideoType.SCREEN_TYPE_DEFAULT, "适应", "画面完整显示，两侧留黑边"),
            Triple(GSYVideoType.SCREEN_MATCH_FULL, "拉伸", "画面铺满，比例可能变形"),
            Triple(GSYVideoType.SCREEN_TYPE_FULL, "裁剪", "等比铺满，裁掉边缘"),
        ).forEach { (type, label, desc) ->
            val chip = makeChip(label, currentScale == type) {
                applyScaleMode(scaleNameOf(type), tip = false)
                showTip("画面比例：$label（$desc）")
            }
            chip.tag = type
            b.scaleChips.addView(chip)
        }
    }

    private fun setupControlChips() {
        b.controlChips.removeAllViews()
        listOf(true to "显示", false to "隐藏").forEach { (enabled, label) ->
            val chip = makeChip(label, controlBarEnabled == enabled) {
                applyControlBarEnabled(enabled)
            }
            chip.tag = enabled
            b.controlChips.addView(chip)
        }
    }

    private fun applySpeed(speed: Float, tip: Boolean = true) {
        currentSpeed = speed.coerceIn(0.25f, 4f)
        runCatching { b.player.setSpeed(currentSpeed) }
        selectChip(b.speedChips, currentSpeed)
        playerPrefs.edit().putFloat("speed", currentSpeed).apply()
        if (tip) showTip("播放速度 ${speedLabel(currentSpeed)}")
    }

    /** @return 比例名是否合法 */
    private fun applyScaleMode(name: String, tip: Boolean = true): Boolean {
        val type = scaleTypeOf(name) ?: return false
        currentScale = type
        applyScale(type)
        selectChip(b.scaleChips, type)
        playerPrefs.edit().putInt("scale", type).apply()
        if (tip) showTip("画面比例：${scaleLabel(type)}")
        return true
    }

    private fun applyControlBarEnabled(enabled: Boolean, tip: Boolean = true) {
        controlBarEnabled = enabled
        selectChip(b.controlChips, enabled)
        playerPrefs.edit().putBoolean("bar", enabled).apply()
        if (enabled) {
            showControlBar(autoHide = true)
            if (tip) showTip("单击画面可显示/隐藏控制条")
        } else {
            uiHandler.removeCallbacks(hideControlBar)
            b.controlBar.isVisible = false
            if (tip) showTip("已隐藏控制条，可在设置中恢复")
        }
    }

    private fun applyScale(type: Int) {
        runCatching {
            GSYVideoType.setShowType(type)
            b.player.changeTextureViewShowType()
        }
    }

    private fun scaleTypeOf(name: String): Int? = when (name.trim().lowercase(Locale.ROOT)) {
        "fit", "auto", "default", "适应" -> GSYVideoType.SCREEN_TYPE_DEFAULT
        "stretch", "match", "拉伸" -> GSYVideoType.SCREEN_MATCH_FULL
        "crop", "full", "裁剪", "铺满" -> GSYVideoType.SCREEN_TYPE_FULL
        else -> null
    }

    private fun scaleNameOf(type: Int): String = when (type) {
        GSYVideoType.SCREEN_MATCH_FULL -> "stretch"
        GSYVideoType.SCREEN_TYPE_FULL -> "crop"
        else -> "fit"
    }

    private fun scaleLabel(type: Int): String = when (type) {
        GSYVideoType.SCREEN_MATCH_FULL -> "拉伸"
        GSYVideoType.SCREEN_TYPE_FULL -> "裁剪"
        else -> "适应"
    }

    private fun speedLabel(speed: Float): String =
        if (speed == speed.toInt().toFloat()) "${speed.toInt()}.0x" else "${speed}x"

    /**
     * 设置面板里的胶囊按钮：用 bg_live_chip 的 selected 态表示当前选项。
     * 同一容器内按 tag 互斥选中。
     */
    private fun makeChip(
        text: String,
        selected: Boolean,
        onClick: () -> Unit,
    ): TextView = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 10f
        isSingleLine = true
        setTextColor(ContextCompat.getColor(context, R.color.live_chip_text))
        background = ContextCompat.getDrawable(context, R.drawable.bg_live_chip)
        isSelected = selected
        isClickable = true
        isFocusable = true
        setPadding(dp(2), dp(6), dp(2), dp(6))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .also { it.marginStart = dp(3); it.marginEnd = dp(3) }
        setOnClickListener {
            onClick()
            resetControlBarTimer()
        }
    }

    private fun selectChip(container: LinearLayout, value: Any) {
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            child.isSelected = child.tag == value
        }
    }

    // ---------------- AI（AppFunctions）控制接口 ----------------

    /** 读取 Intent 里 AI 指定的初始倍速 / 比例 / 侧栏 / 控制条 / 线路 */
    private fun applyAiExtras() {
        val i = intent
        if (i.hasExtra(EXTRA_AI_SPEED)) applySpeed(i.getFloatExtra(EXTRA_AI_SPEED, currentSpeed), tip = false)
        i.getStringExtra(EXTRA_AI_SCALE)?.takeIf { it.isNotBlank() }?.let { applyScaleMode(it, tip = false) }
        if (i.hasExtra(EXTRA_AI_SIDEBAR)) applySidePanelVisible(i.getBooleanExtra(EXTRA_AI_SIDEBAR, true))
        if (i.hasExtra(EXTRA_AI_CONTROLS)) applyControlBarEnabled(i.getBooleanExtra(EXTRA_AI_CONTROLS, true), tip = false)
        preferredLine = i.getStringExtra(EXTRA_AI_LINE)?.takeIf { it.isNotBlank() }
    }

    /** 当前播放状态快照（供 AI 查询） */
    fun aiSnapshot(): LivePlayerSnapshot = LivePlayerSnapshot(
        mode = mode,
        title = title,
        playing = currentLink != null && !userPaused,
        paused = userPaused,
        speed = currentSpeed,
        scale = scaleNameOf(currentScale),
        sidebarVisible = b.sidePanel.isVisible,
        controlBarVisible = b.controlBar.isVisible,
        controlsEnabled = controlBarEnabled,
        currentLine = currentLink?.name.orEmpty(),
        lines = links.map { it.name },
        positionMs = runCatching { b.player.currentPositionWhenPlaying }.getOrDefault(0L).coerceAtLeast(0L),
        // 直播 / 重连瞬间 GSY 可能返回 Long.MIN 等异常值，统一归零
        durationMs = runCatching { b.player.duration }.getOrDefault(0L)
            .takeIf { it in 1..(24L * 3600 * 1000) } ?: 0L,
    )

    /**
     * AI 控制播放器：所有参数均为空表示只读状态。
     * 参数非法或线路不存在时不会中断其它已生效项，失败原因通过 [LivePlayerControlResult.message] 返回。
     */
    fun aiControl(
        speed: Float? = null,
        scale: String? = null,
        sidebarVisible: Boolean? = null,
        controlsVisible: Boolean? = null,
        lineName: String? = null,
        paused: Boolean? = null,
        positionPercent: Int? = null,
    ): LivePlayerControlResult {
        val errors = mutableListOf<String>()
        var applied = false

        speed?.let {
            applySpeed(it)
            applied = true
        }
        scale?.takeIf { it.isNotBlank() }?.let {
            if (applyScaleMode(it)) applied = true else errors += "无法识别的画面比例：$it（可用 fit/stretch/crop）"
        }
        sidebarVisible?.let {
            applySidePanelVisible(it)
            applied = true
        }
        controlsVisible?.let {
            applyControlBarEnabled(it)
            applied = true
        }
        lineName?.takeIf { it.isNotBlank() }?.let { query ->
            val link = linkByName(query)
            if (link == null) {
                errors += "未找到线路：$query"
            } else {
                play(link)
                showTip("已切换到 ${link.name}")
                applied = true
            }
        }
        paused?.let {
            if (it != userPaused) togglePlay()
            applied = true
        }
        positionPercent?.let {
            if (mode != MODE_REPLAY) {
                errors += "直播不支持拖动进度"
            } else {
                seekToPercent(it)
                applied = true
            }
        }

        if (errors.isNotEmpty()) showTip(errors.joinToString("；"), 3000L)
        val message = if (errors.isEmpty()) "" else errors.joinToString("；")
        return LivePlayerControlResult(applied, message, aiSnapshot())
    }

    private fun seekToPercent(percent: Int) {
        val duration = runCatching { b.player.duration }.getOrDefault(0L)
        if (duration <= 0) return
        val p = percent.coerceIn(0, 100)
        val position = duration * p / 100
        runCatching { b.player.seekTo(position) }
        b.seekBar.progress = p * 10
        b.tvCur.text = timeText(position)
        showControlBar(autoHide = true)
    }

    /** 按线路名/URL 查找：精确名 → 包含名 → 反向包含，方便 AI 用「线路C4」「C4」等写法 */
    private fun linkByName(query: String): LiveLink? {
        val q = query.trim()
        if (q.isEmpty()) return null
        return links.firstOrNull { it.url == q }
            ?: links.firstOrNull { it.name.equals(q, ignoreCase = true) }
            ?: links.firstOrNull { it.name.contains(q, ignoreCase = true) }
            ?: links.firstOrNull { q.contains(it.name, ignoreCase = true) }
    }

    // ---------------- 播放器手势 ----------------

    private fun setupGestures() {
        b.gestureLayer.setOnTouchListener { _, ev -> handleGesture(ev) }
    }

    /**
     * 手势规则（播放页左半边画面）：
     * - 双击：暂停 / 继续
     * - 长按：2 倍速，松开恢复
     * - 左右滑：录像快进快退（直播不支持，提示一次）
     * - 上下滑：左半亮度、右半音量
     */
    private fun handleGesture(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downAt = System.currentTimeMillis()
                gestureMode = GestureMode.NONE
                seekStart = b.player.currentPositionWhenPlaying
                seekTarget = seekStart
                startBrightness = currentBrightness()
                startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                speedBoosted = false
                uiHandler.postDelayed(longPressRunnable, 500L)
            }

            MotionEvent.ACTION_MOVE -> {
                if (speedBoosted) return true
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (gestureMode == GestureMode.NONE &&
                    (abs(dx) > touchSlop || abs(dy) > touchSlop)
                ) {
                    uiHandler.removeCallbacks(longPressRunnable)
                    gestureMode = when {
                        abs(dx) > abs(dy) -> GestureMode.SEEK
                        downX < b.gestureLayer.width / 2f -> GestureMode.BRIGHTNESS
                        else -> GestureMode.VOLUME
                    }
                }
                when (gestureMode) {
                    GestureMode.SEEK -> previewSeek(dx)
                    GestureMode.BRIGHTNESS -> adjustBrightness(dy)
                    GestureMode.VOLUME -> adjustVolume(dy)
                    GestureMode.NONE -> {}
                }
            }

            MotionEvent.ACTION_UP -> {
                uiHandler.removeCallbacks(longPressRunnable)
                val moved = abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop
                when {
                    speedBoosted -> stopSpeedBoost()
                    gestureMode == GestureMode.SEEK -> commitSeek()
                    gestureMode != GestureMode.NONE -> hideGestureOsd()
                    !moved && System.currentTimeMillis() - downAt < 300L -> handleTap()
                }
                gestureMode = GestureMode.NONE
            }

            MotionEvent.ACTION_CANCEL -> {
                uiHandler.removeCallbacks(longPressRunnable)
                if (speedBoosted) stopSpeedBoost()
                hideGestureOsd()
                gestureMode = GestureMode.NONE
            }
        }
        return true
    }

    /** 单击：先等 300ms 确认不是双击，再切换控制条；首次给出手势引导 */
    private fun handleTap() {
        val now = System.currentTimeMillis()
        if (now - lastTapAt <= 300L) {
            lastTapAt = 0L
            uiHandler.removeCallbacks(singleTapRunnable)
            togglePlay()
            return
        }
        lastTapAt = now
        uiHandler.removeCallbacks(singleTapRunnable)
        uiHandler.postDelayed(singleTapRunnable, 300L)
        if (gestureHintShown) return
        gestureHintShown = true
        val hint = if (mode == MODE_REPLAY) {
            "单击显示控制条 · 双击暂停 · 长按倍速 · 左右滑调进度"
        } else {
            "单击显示控制条 · 双击暂停 · 长按倍速 · 上下滑调音量/亮度"
        }
        showGestureOsd(hint, autoHideMs = 3000L)
    }

    /** 单击画面：关闭设置面板，或显示/隐藏控制条 */
    private fun onSingleTap() {
        if (b.settingsPanel.isVisible) {
            b.settingsPanel.isVisible = false
            resetControlBarTimer()
            return
        }
        if (!controlBarEnabled) return
        if (b.controlBar.isVisible) {
            uiHandler.removeCallbacks(hideControlBar)
            b.controlBar.isVisible = false
        } else {
            showControlBar(autoHide = true)
        }
    }

    private fun togglePlay() {
        if (userPaused) {
            userPaused = false
            b.player.onVideoResume()
            showGestureOsd("继续播放")
            if (controlBarEnabled) showControlBar(autoHide = true)
        } else {
            userPaused = true
            b.player.onVideoPause()
            showGestureOsd("已暂停")
            // 暂停后固定显示控制条，方便拖动进度
            if (controlBarEnabled) showControlBar(autoHide = false)
        }
        updatePlayPauseIcon()
        // 播放器暂停/恢复时会自己弹出底部控件，这里统一隐藏
        hideNativeControls()
    }

    /**
     * 彻底移除 GSY 自带的控件：暂停按钮、直播无意义的进度条与时间。
     * 只设 GONE 会被 GSY 在缓冲/尺寸变化时重新显示，直接从父容器摘掉才稳定；
     * 本页所有交互由手势层与右侧信息栏承担。
     */
    private fun hideNativeControls() {
        listOf(R.id.layout_bottom, R.id.start, R.id.bottom_progressbar).forEach { id ->
            runCatching {
                val v = b.player.findViewById<View>(id) ?: return@runCatching
                (v.parent as? ViewGroup)?.removeView(v)
            }
        }
    }

    private fun startSpeedBoost() {
        if (isFinishing || isDestroyed) return
        if (b.tvError.isVisible || userPaused) return
        speedBoosted = true
        runCatching { b.player.setSpeed(2f) }
        showGestureOsd("2 倍速播放中", autoHideMs = 0L)
    }

    private fun stopSpeedBoost() {
        speedBoosted = false
        // 松手后回到用户选择的倍速（默认 1x），而不是写死 1f
        runCatching { b.player.setSpeed(currentSpeed) }
        hideGestureOsd()
    }

    /** 左右滑：全屏宽 ≈ ±2 分钟，松手才真正跳转 */
    private fun previewSeek(dx: Float) {
        if (mode != MODE_REPLAY) {
            if (!liveSeekHintShown) {
                liveSeekHintShown = true
                showGestureOsd("直播不支持拖动进度", autoHideMs = 1500L)
            }
            return
        }
        val duration = b.player.duration
        if (duration <= 0) return
        val width = b.gestureLayer.width.coerceAtLeast(1)
        val delta = (dx / width * 120_000L).toLong()
        seekTarget = (seekStart + delta).coerceIn(0L, duration)
        val percent = (seekTarget * 100 / duration).toInt()
        showGestureOsd("${timeText(seekTarget)} / ${timeText(duration)}", percent, 1500L)
    }

    private fun commitSeek() {
        if (mode == MODE_REPLAY && seekTarget > 0) {
            runCatching { b.player.seekTo(seekTarget) }
            hideNativeControls()
        }
        hideGestureOsd()
    }

    private fun adjustBrightness(dy: Float) {
        val ratio = -dy / b.gestureLayer.height.coerceAtLeast(1)
        val value = (startBrightness + ratio).coerceIn(0.05f, 1f)
        val attrs = window.attributes
        attrs.screenBrightness = value
        window.attributes = attrs
        val percent = (value * 100).roundToInt()
        showGestureOsd("亮度 $percent%", percent, 1500L)
    }

    private fun adjustVolume(dy: Float) {
        val ratio = -dy / b.gestureLayer.height.coerceAtLeast(1)
        val target = (startVolume + ratio * maxVolume).roundToInt().coerceIn(0, maxVolume)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
        val percent = target * 100 / maxVolume
        showGestureOsd("音量 $percent%", percent, 1500L)
    }

    /** 当前亮度：窗口未设置(-1)时取系统亮度 */
    private fun currentBrightness(): Float {
        val win = window.attributes.screenBrightness
        if (win >= 0f) return win
        val sys = runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128)
        return (sys / 255f).coerceIn(0.05f, 1f)
    }

    private fun timeText(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) {
            String.format(Locale.CHINA, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.CHINA, "%02d:%02d", m, s)
        }
    }

    /**
     * 手势反馈浮层。
     * @param progress >= 0 显示进度条，-1 仅文字
     * @param autoHideMs 0 表示常驻（长按倍速期间）
     */
    private fun showGestureOsd(text: String, progress: Int = -1, autoHideMs: Long = 1200L) {
        if (isFinishing || isDestroyed) return
        uiHandler.removeCallbacks(hideOsd)
        b.tvGesture.text = text
        b.gestureProgress.isVisible = progress >= 0
        if (progress >= 0) b.gestureProgress.progress = progress
        b.gestureOsd.isVisible = true
        if (autoHideMs > 0) uiHandler.postDelayed(hideOsd, autoHideMs)
    }

    private fun hideGestureOsd() {
        uiHandler.removeCallbacks(hideOsd)
        if (b.gestureOsd.isVisible) b.gestureOsd.isVisible = false
    }

    override fun onPause() {
        super.onPause()
        if (LiveApi.onSiteSwitch === siteSwitchListener) LiveApi.onSiteSwitch = null
        // 页面不可见时结束长按倍速与手势浮层，避免恢复后仍是 2 倍速
        if (speedBoosted) stopSpeedBoost()
        hideGestureOsd()
        uiHandler.removeCallbacks(longPressRunnable)
        uiHandler.removeCallbacks(singleTapRunnable)
        uiHandler.removeCallbacks(hideControlBar)
        uiHandler.removeCallbacks(progressTicker)
        lastTapAt = 0L
        b.player.onVideoPause()
    }

    override fun onResume() {
        super.onResume()
        LiveApi.onSiteSwitch = siteSwitchListener
        // 用户主动暂停时，回到前台不自动恢复播放
        if (!userPaused) b.player.onVideoResume()
        hideNativeControls()
        uiHandler.removeCallbacks(progressTicker)
        uiHandler.post(progressTicker)
    }

    override fun onDestroy() {
        LivePlayerBridge.detach(this)
        if (LiveApi.onSiteSwitch === siteSwitchListener) LiveApi.onSiteSwitch = null
        uiHandler.removeCallbacksAndMessages(null)
        GSYVideoManager.releaseAllVideos()
        super.onDestroy()
    }
}
