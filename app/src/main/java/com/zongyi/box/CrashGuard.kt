package com.zongyi.box

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.zongyi.box.ui.MainActivity
import java.io.File

/**
 * 全局崩溃防护（TV 高可靠性要求）：
 *
 * 1. 捕获全部未捕获异常，落盘到 filesDir/crash_last.txt（设备信息 + 堆栈），
 *    下次启动时在主界面弹出报告，用户可复制反馈——真机 TV 无法连 adb 时也能定位问题。
 * 2. 主线程崩溃：不直接杀进程，自动重启主界面（CLEAR_TASK 清理破损页面），
 *    并重新接管主线程消息循环让进程存活；60 秒内连续崩溃超 3 次才交还系统
 *    （防止 onCreate 必崩场景的无限重启循环）。
 * 3. 非主线程崩溃：吞掉并落盘，仅结束该线程，进程不退出。
 */
object CrashGuard {

    private var appCtx: Context? = null
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var crashCount = 0
    private var windowStartAt = 0L

    /** 必须最先在 Application.onCreate 调用 */
    fun install(app: android.app.Application) {
        if (appCtx != null) return
        appCtx = app.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> handle(t, e) }
    }

    private fun handle(thread: Thread, e: Throwable) {
        try { persist(thread, e) } catch (_: Throwable) {}
        val ctx = appCtx
        val isMain = thread === Looper.getMainLooper().thread
        if (!isMain || ctx == null) {
            // 非主线程（或上下文未就绪）：吞掉异常，线程结束但进程存活
            return
        }
        // 主线程崩溃：限流后尝试恢复
        val now = System.currentTimeMillis()
        if (now - windowStartAt > 60_000) {
            windowStartAt = now
            crashCount = 0
        }
        crashCount++
        if (crashCount > 3) {
            // 短时间连环崩溃：大概率 onCreate 必崩，交还系统终止（避免无限重启循环）
            defaultHandler?.uncaughtException(thread, e)
            return
        }
        try { recover(ctx) } catch (_: Throwable) {}
        // 接管主线程消息循环，进程继续存活（重新进入 loop 处理重启任务等后续消息）
        while (true) {
            try {
                Looper.loop()
            } catch (e2: Throwable) {
                try { persist(thread, e2) } catch (_: Throwable) {}
                try { recover(ctx) } catch (_: Throwable) {}
            }
        }
    }

    /** 崩溃恢复：提示 + 清空任务栈重启主界面 */
    private fun recover(ctx: Context) {
        try {
            Toast.makeText(ctx, "应用出现异常，已自动恢复", Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {}
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                ctx.startActivity(Intent(ctx, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                })
            } catch (_: Throwable) {}
        }, 300)
    }

    private fun persist(thread: Thread, e: Throwable) {
        val ctx = appCtx ?: return
        val sb = StringBuilder()
        sb.append("时间: ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
            .format(java.util.Date())).append('\n')
        sb.append("设备: ").append(android.os.Build.MANUFACTURER).append(' ')
            .append(android.os.Build.MODEL)
            .append("  Android ").append(android.os.Build.VERSION.RELEASE)
            .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
        sb.append("线程: ").append(thread.name).append('\n')
        sb.append("进程: pid=").append(android.os.Process.myPid())
            .append(" 上次崩溃计数=").append(crashCount).append("\n\n")
        sb.append(android.util.Log.getStackTraceString(e))
        try {
            File(ctx.filesDir, "crash_last.txt").writeText(sb.toString())
        } catch (_: Throwable) {}
        // 永远同时输出到 logcat：能连 adb 时可直接抓取
        try {
            android.util.Log.e("ZongYiBox-Crash", sb.toString())
        } catch (_: Throwable) {}
    }

    /** 读取上次崩溃报告；无则返回 null */
    fun lastCrash(ctx: Context): String? = try {
        val f = File(ctx.filesDir, "crash_last.txt")
        if (f.exists() && f.length() > 0) f.readText() else null
    } catch (_: Throwable) { null }

    /** 清除崩溃报告 */
    fun clearLastCrash(ctx: Context) {
        try { File(ctx.filesDir, "crash_last.txt").delete() } catch (_: Throwable) {}
    }
}
