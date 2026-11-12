package com.zongyi.box.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.zongyi.box.BuildConfig
import com.zongyi.box.R
import com.zongyi.box.data.UpdateChecker
import com.zongyi.box.data.UpdateConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * 更新提示 UI 层：
 *
 * - [autoCheck]：启动后静默检查（节流 + 可跳过版本），有新版才弹窗；
 * - [manualCheck]：「我的」页手动检查，任何结果都给 Toast 反馈；
 * - 弹窗内的下载 / 安装权限引导 / 拉起系统安装器也在这里。
 *
 * 下载用 cacheDir，交给 FileProvider 授权给系统安装器读取，
 * 不需要 WRITE_EXTERNAL_STORAGE（Android 10+ 分区存储下也不可用）。
 */
object UpdatePrompt {

    private const val SP = "update_prefs"
    private const val KEY_LAST_CHECK = "last_check_at"
    private const val KEY_SKIP_CODE = "skip_version_code"

    /** 启动后延迟，先让开屏广告和首屏渲染完 */
    private const val AUTO_CHECK_DELAY_MS = 2500L

    private var checkJob: Job? = null

    // ---------- 入口 ----------

    /** 启动自动检查：同一版本用户点过「以后再说」就不再打扰，换新版本会重新提示 */
    fun autoCheck(activity: FragmentActivity) {
        if (!UpdateConfig.isConfigured || !UpdateConfig.AUTO_CHECK_ON_START) return
        val sp = activity.getSharedPreferences(SP, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - sp.getLong(KEY_LAST_CHECK, 0L) < UpdateConfig.CHECK_THROTTLE_MS) return
        sp.edit().putLong(KEY_LAST_CHECK, now).apply()

        checkJob?.cancel()
        checkJob = activity.lifecycleScope.launch {
            delay(AUTO_CHECK_DELAY_MS)
            val release = runCatching { UpdateChecker.fetch() }.getOrNull() ?: return@launch
            if (activity.isFinishing || activity.isDestroyed) return@launch
            if (!UpdateChecker.isNewer(release, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)) return@launch
            val force = UpdateChecker.isForceUpdate(release, BuildConfig.VERSION_CODE)
            // 主动忽略过的版本，自动检查时不再弹（强制更新除外）
            if (!force && sp.getInt(KEY_SKIP_CODE, 0) == release.versionCode) return@launch
            show(activity, release, force)
        }
    }

    /** 手动检查：未配置更新源 / 无网络 / 已最新 都给出明确反馈 */
    fun manualCheck(activity: FragmentActivity) {
        if (!UpdateConfig.isConfigured) {
            toast(activity, "更新源未配置：请在 UpdateConfig.kt 填入发布仓库")
            return
        }
        if (checkJob?.isActive == true) return
        toast(activity, "正在检查更新…")
        checkJob = activity.lifecycleScope.launch {
            val result = runCatching { UpdateChecker.fetch() }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            val release = result.getOrNull()
            when {
                release == null -> toast(activity, "检查失败，请稍后重试")
                !UpdateChecker.isNewer(release, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME) ->
                    toast(activity, "已是最新版本 v${BuildConfig.VERSION_NAME}")
                else -> show(
                    activity, release,
                    UpdateChecker.isForceUpdate(release, BuildConfig.VERSION_CODE)
                )
            }
        }
    }

    /** 当前版本号文案，如 "v1.0.0" */
    fun localVersionText(): String = "v${BuildConfig.VERSION_NAME}"

    // ---------- 弹窗 ----------

    fun show(activity: FragmentActivity, release: UpdateChecker.Release, force: Boolean) {
        val dialog = Dialog(activity, R.style.Theme_ZongYiBox_Dialog)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_update, null)
        dialog.setContentView(view)
        dialog.setCancelable(!force)
        dialog.setCanceledOnTouchOutside(false)

        val tvTitle = view.findViewById<TextView>(R.id.tvUpdateTitle)
        val tvMeta = view.findViewById<TextView>(R.id.tvUpdateMeta)
        val tvChangelog = view.findViewById<TextView>(R.id.tvChangelog)
        val progressBox = view.findViewById<View>(R.id.progressBox)
        val progressBar = view.findViewById<ProgressBar>(R.id.progressBar)
        val tvProgress = view.findViewById<TextView>(R.id.tvProgress)
        val btnLater = view.findViewById<TextView>(R.id.btnLater)
        val btnUpdate = view.findViewById<TextView>(R.id.btnUpdate)

        tvTitle.text = "发现新版本 v${release.versionName}"
        val size = UpdateChecker.humanSize(release.sizeBytes)
        tvMeta.text = buildString {
            append("当前版本 v${BuildConfig.VERSION_NAME}")
            if (size.isNotBlank()) append(" · 新版本 $size")
            if (release.forceBelow > 0 && BuildConfig.VERSION_CODE < release.forceBelow) {
                append(" · 该版本已停止支持")
            }
        }
        tvChangelog.text = release.changelog.ifBlank { "· 优化播放体验，修复已知问题" }
        tvChangelog.movementMethod = android.text.method.ScrollingMovementMethod.getInstance()
        btnUpdate.text = if (force) "立即更新（必须）" else "立即更新"

        if (force) {
            btnLater.text = "退出应用"
            btnLater.setOnClickListener { activity.finish() }
        } else {
            btnLater.text = "以后再说"
            btnLater.setOnClickListener {
                activity.getSharedPreferences(SP, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_SKIP_CODE, release.versionCode).apply()
                dialog.dismiss()
            }
        }

        var downloading = false
        btnUpdate.setOnClickListener {
            if (downloading) return@setOnClickListener
            // 已有完整安装包（例如刚去开过安装权限）直接安装，不重复下载
            val cached = UpdateChecker.cachedApk(activity, release)
            if (cached != null) {
                dialog.dismiss()
                install(activity, cached)
                return@setOnClickListener
            }
            if (!ensureInstallPermission(activity)) return@setOnClickListener

            downloading = true
            btnUpdate.isEnabled = false
            btnUpdate.alpha = 0.5f
            btnLater.isEnabled = false
            progressBox.visibility = View.VISIBLE
            progressBar.progress = 0
            tvProgress.text = "准备下载…"

            var finished = false
            val job = activity.lifecycleScope.launch {
                try {
                    val apk = UpdateChecker.download(activity, release) { done, total ->
                        activity.runOnUiThread {
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                                progressBar.progress = pct
                                tvProgress.text =
                                    "下载中 $pct%  ${UpdateChecker.humanSize(done)} / ${UpdateChecker.humanSize(total)}"
                            } else {
                                tvProgress.text = "下载中 ${UpdateChecker.humanSize(done)}"
                            }
                        }
                    }
                    if (activity.isFinishing || activity.isDestroyed) return@launch
                    progressBar.progress = 100
                    tvProgress.text = "下载完成，正在安装…"
                    finished = true
                    dialog.dismiss()
                    install(activity, apk)
                } catch (e: Exception) {
                    if (activity.isFinishing || activity.isDestroyed) return@launch
                    downloading = false
                    btnUpdate.isEnabled = true
                    btnUpdate.alpha = 1f
                    btnLater.isEnabled = true
                    progressBox.visibility = View.GONE
                    toast(activity, "更新失败：${e.message ?: "网络异常"}")
                }
            }
            // 弹窗消失时取消未完成的下载，避免后台继续占用流量
            dialog.setOnDismissListener { if (!finished) job.cancel() }
        }

        dialog.show()
        // 宽度在 show 之后设置才不被窗口默认值覆盖
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.86f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    // ---------- 安装 ----------

    /** Android 8.0+ 需「安装未知应用」授权；未授权时引导到系统设置 */
    private fun ensureInstallPermission(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        if (activity.packageManager.canRequestPackageInstalls()) return true
        AlertDialog.Builder(activity, R.style.Theme_ZongYiBox)
            .setTitle("需要安装权限")
            .setMessage("请允许「综艺盒子」安装应用，返回后再次点击「立即更新」即可。")
            .setPositiveButton("去设置") { _, _ ->
                runCatching {
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                            .setData(Uri.parse("package:${activity.packageName}"))
                    )
                }.onFailure {
                    runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)) }
                }
            }
            .setNegativeButton("取消", null)
            .show()
        return false
    }

    private fun install(activity: Activity, apk: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                activity, "${activity.packageName}.fileprovider", apk
            )
            activity.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.onFailure {
            toast(activity, "无法拉起安装器：${it.message ?: "请检查安装权限"}")
        }
    }

    private fun toast(ctx: Context, msg: String) {
        runCatching { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    }
}
