package com.zongyi.box.ui

import android.annotation.SuppressLint
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
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
 * 原生播放器（Media3/ExoPlayer + HLS）。
 * 功能：m3u8 流解析播放、清晰度切换、多线路切换、选集/上下集、
 * 手势（亮度/音量/快进快退/双击暂停）、倍速、断点续播、历史记录。
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null

    private var vodId = -1
    private var sid = -1
    private var nid = -1
    private var title = ""
    private var detail: VodDetail? = null
    private var resumed = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
    private var speedIndex = 2

    // 播放错误自动重试
    private var retryCount = 0
    private var retryPending = false
    private val MAX_RETRY = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

        vodId = intent.getIntExtra("id", -1)
        sid = intent.getIntExtra("sid", -1)
        nid = intent.getIntExtra("nid", 1)
        title = intent.getStringExtra("title").orEmpty()
        b.tvTitle.text = title

        b.btnBack.setOnClickListener { finish() }
        b.btnEpisodes.setOnClickListener { showEpisodeSheet() }
        b.btnLine.setOnClickListener { showLineSheet() }
        b.btnQuality.setOnClickListener { showQualityDialog() }
        b.btnSpeed.setOnClickListener { showSpeedDialog() }
        b.btnPrev.setOnClickListener { gotoEpisode(-1) }
        b.btnNext.setOnClickListener { gotoEpisode(1) }
        b.tvError.setOnClickListener { play() }

        buildPlayer()
        setupGestures()
        play()
    }

    private fun buildPlayer() {
        val ts = DefaultTrackSelector(this)
        trackSelector = ts
        // 视频流（m3u8/ts 分片）请求带上浏览器头，与页面请求保持一致的伪装
        val dsFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(BrowserHeaders.USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(BrowserHeaders.media().toMutableMap())
        val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(dsFactory)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(ts)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(30_000, 90_000, 1_500, 5_000)
                    .build()
            )
            .build()
        b.playerView.player = player
        b.playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { vis ->
            b.topBar.visibility = if (vis == View.VISIBLE) View.VISIBLE else View.INVISIBLE
        })
        player!!.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                saveHistory()
                // 网络波动自动重试（递增间隔 1.5s/3s/4.5s），重试期间不叠加
                if (retryPending) return
                if (retryCount < MAX_RETRY) {
                    retryPending = true
                    retryCount++
                    showTip("网络波动，自动重试 $retryCount/$MAX_RETRY…")
                    uiHandler.postDelayed({
                        retryPending = false
                        val p = player ?: return@postDelayed
                        if (isFinishing || isDestroyed) return@postDelayed
                        p.prepare()
                        p.play()
                    }, retryCount * 1500L)
                } else {
                    b.tvError.isVisible = true
                    b.tvError.text = "播放失败，已自动重试 $MAX_RETRY 次\n点击重试，或切换其他线路"
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    // 恢复播放即视为成功，重置重试计数并隐藏错误提示
                    retryCount = 0
                    retryPending = false
                    b.tvError.isVisible = false
                }
                if (!isPlaying) saveHistory()
            }
        })
        // 错误提示可点击：手动重试（重新走完整取流流程）
        b.tvError.setOnClickListener {
            b.tvError.isVisible = false
            retryCount = 0
            retryPending = false
            play()
        }
    }

    private fun play() {
        b.tvError.isVisible = false
        retryCount = 0
        retryPending = false
        lifecycleScope.launch {
            runSuspendCatching {
                val d = async { detail ?: SiteApi.detail(vodId).also { detail = it } }
                val stream = async { SiteApi.stream(vodId, sid, nid) }
                d.await() to stream.await()
            }.onSuccess { (d, stream) ->
                startStream(stream.url, stream.from)
            }.onFailure { e ->
                if (e is CancellationException) return@onFailure
                b.tvError.isVisible = true
                b.tvError.text = "获取视频地址失败：${e.message}\n点击重试，或切换线路"
            }
        }
    }

    private fun startStream(url: String, from: String) {
        val p = player ?: return
        b.playerView.showController()
        val item = MediaItem.Builder()
            .setUri(url)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()
        p.setMediaItem(item, resumePos())
        p.setPlaybackParameters(PlaybackParameters(speeds[speedIndex]))
        p.prepare()
        p.play()
        resumed = true
        saveHistory()
    }

    private fun resumePos(): Long {
        if (resumed) return C.TIME_UNSET
        val pos = Store.resumeOf(this, vodId, sid, nid)
        return if (pos > 30_000) pos else C.TIME_UNSET
    }

    // ---------- 手势 ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        var brightnessBase = -1f
        var volumeBase = 0
        var seekBase = 0L
        var gestureMode = 0 // 0=未知 1=亮度 2=音量 3=快进
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                gestureMode = 0
                brightnessBase = window.attributes.screenBrightness.takeIf { it >= 0 } ?: 0.5f
                volumeBase = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                seekBase = player?.currentPosition ?: 0L
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                val p = player ?: return true
                val totalX = e2.x - (e1?.x ?: e2.x)
                val totalY = e2.y - (e1?.y ?: e2.y)
                if (gestureMode == 0) {
                    gestureMode = when {
                        kotlin.math.abs(totalX) > kotlin.math.abs(totalY) -> 3
                        (e1?.x ?: 0f) < b.root.width / 2 -> 1
                        else -> 2
                    }
                }
                when (gestureMode) {
                    1 -> { // 左半屏上下滑 = 亮度
                        val delta = -totalY / b.root.height * 1.2f
                        val v = (brightnessBase + delta).coerceIn(0.01f, 1f)
                        window.attributes = window.attributes.apply { screenBrightness = v }
                        showTip("亮度 ${"%.0f".format(v * 100)}%")
                    }
                    2 -> { // 右半屏上下滑 = 音量
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val delta = (-totalY / b.root.height * max * 1.5f).toInt()
                        val v = (volumeBase + delta).coerceIn(0, max)
                        if (v != volumeBase) audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                        showTip("音量 ${v * 100 / max}%")
                    }
                    3 -> { // 水平滑 = 快进快退
                        val dur = p.duration.takeIf { it > 0 } ?: return true
                        val step = (totalX * (dur / b.root.width) * 1.5).toLong()
                        val target = (seekBase + step).coerceIn(0, dur)
                        p.seekTo(target)
                        showTip("${fmtMs(target)} / ${fmtMs(dur)}")
                    }
                }
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (b.topBar.visibility == View.VISIBLE) b.playerView.hideController()
                else b.playerView.showController()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val p = player ?: return true
                if (p.isPlaying) p.pause() else p.play()
                showTip(if (p.isPlaying) "播放" else "暂停")
                return true
            }
        })
        b.gestureLayer.setOnTouchListener { v, e ->
            gd.onTouchEvent(e)
            true
        }
    }

    private val tipRunnable = Runnable {
        if (!isFinishing && !isDestroyed) b.gestureTip.isVisible = false
    }

    private fun showTip(text: String) {
        b.gestureTip.text = text
        b.gestureTip.isVisible = true
        uiHandler.removeCallbacks(tipRunnable)
        uiHandler.postDelayed(tipRunnable, 800)
    }

    private fun fmtMs(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    // ---------- 历史 ----------

    private fun saveHistory() {
        val p = player ?: return
        val d = detail
        val epName = d?.source(sid)?.episodes?.firstOrNull { it.nid == nid }?.name
            ?: intent.getStringExtra("episodeName").orEmpty()
        if (p.currentPosition > 5_000) {
            Store.saveHistory(
                this,
                HistoryEntry(
                    vodId = vodId,
                    title = d?.title ?: title,
                    cover = d?.cover ?: intent.getStringExtra("cover").orEmpty(),
                    sid = sid, nid = nid,
                    episodeName = epName,
                    positionMs = p.currentPosition,
                    durationMs = p.duration.takeIf { it > 0 } ?: 0L,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    // ---------- 选集 / 上下集 ----------

    private fun currentSource() = detail?.source(sid) ?: detail?.firstAvailable()

    private fun switchTo(ep: Episode) {
        nid = ep.nid
        resumed = false
        play()
        saveHistory()
    }

    private fun gotoEpisode(offset: Int) {
        val src = currentSource() ?: return
        val idx = src.episodes.indexOfFirst { it.nid == nid }
        val target = src.episodes.getOrNull(idx + offset) ?: run {
            showTip(if (offset > 0) "已经是最后一集" else "已经是第一集")
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
        dialog.setContentView(wrapSheet(group))
        dialog.show()
    }

    // ---------- 线路 / 画质 / 倍速 ----------

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
        dialog.setContentView(wrapSheet(group))
        dialog.show()
    }

    private fun showQualityDialog() {
        val p = player ?: return
        TrackSelectionDialogBuilder(this, "画质选择", p, C.TRACK_TYPE_VIDEO)
            .setTheme(R.style.Theme_ZongYiBox)
            .build()
            .show()
    }

    private fun showSpeedDialog() {
        val items = speeds.map { if (it == 1.0f) "1.0x 常速" else "${it}x" }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_ZongYiBox)
            .setTitle("倍速播放")
            .setSingleChoiceItems(items, speedIndex) { dlg, which ->
                speedIndex = which
                player?.setPlaybackParameters(PlaybackParameters(speeds[which]))
                dlg.dismiss()
            }
            .show()
    }

    private fun wrapSheet(view: View): View {
        return androidx.core.widget.NestedScrollView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(view, android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            setBackgroundColor(getColor(R.color.surface))
            setPadding(48, 56, 48, 56)
        }
    }

    // ---------- 生命周期 ----------

    override fun onPause() {
        super.onPause()
        saveHistory()
        player?.pause()
    }

    override fun onStop() {
        super.onStop()
        saveHistory()
    }

    override fun onDestroy() {
        b.playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
