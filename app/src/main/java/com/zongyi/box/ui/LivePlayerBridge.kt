package com.zongyi.box.ui

import java.lang.ref.WeakReference

/**
 * 直播播放器状态快照（供 AppFunctions / AI 读取）。
 * 所有时间均为毫秒。
 */
data class LivePlayerSnapshot(
    val mode: String,
    val title: String,
    val playing: Boolean,
    val paused: Boolean,
    val speed: Float,
    val scale: String,
    val sidebarVisible: Boolean,
    val controlBarVisible: Boolean,
    val controlsEnabled: Boolean,
    val currentLine: String,
    val lines: List<String>,
    val positionMs: Long,
    val durationMs: Long,
)

/**
 * AI 控制播放器的结果：[applied] 表示至少有一项生效，[message] 为失败原因说明。
 */
data class LivePlayerControlResult(
    val applied: Boolean,
    val message: String,
    val state: LivePlayerSnapshot?,
)

/**
 * 播放页与 AppFunctions 之间的桥。
 *
 * AppFunctionService 与 Activity 同进程，用弱引用拿到当前打开的播放页，
 * AI 便可在播放过程中调整倍速、画面比例、侧栏/控制条显隐、切换线路与暂停。
 * 未打开播放页时 [current] 返回 null，调用方应提示先开始播放。
 */
object LivePlayerBridge {

    private var ref: WeakReference<LivePlayerActivity>? = null

    fun attach(activity: LivePlayerActivity) {
        ref = WeakReference(activity)
    }

    fun detach(activity: LivePlayerActivity) {
        if (ref?.get() === activity) ref = null
    }

    fun current(): LivePlayerActivity? =
        ref?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
}
