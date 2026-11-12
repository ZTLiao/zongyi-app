package com.zongyi.box.data

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 版本更新检查（零服务端）：
 *
 * 1. 按 [UpdateConfig.manifestUrls] 顺序拉取静态清单 update.json，取第一个成功的；
 * 2. 用清单里的 versionCode 与本地 BuildConfig.VERSION_CODE 比对；
 * 3. 下载走清单同源的相对路径解析，GitHub 地址自动补 ghproxy 镜像，逐个尝试；
 * 4. 下载完成校验大小与 sha256，再交给 UpdatePrompt 拉起系统安装器。
 *
 * 清单格式（UTF-8，随 Release 附件一起上传）：
 * ```
 * {
 *   "versionCode": 2,
 *   "versionName": "1.0.1",
 *   "apk": "app-release.apk",
 *   "size": 18342016,
 *   "sha256": "...",
 *   "forceBelow": 0,
 *   "publishedAt": "2026-11-12",
 *   "changelog": "· 修复播放器偶发黑屏\n· 新增倍速记忆"
 * }
 * ```
 * `apk` 可以是相对路径（相对清单所在目录）或绝对 http(s) 地址；
 * 也可用 `apkUrls` 数组显式指定多个下载地址。
 * `forceBelow` 大于 0 时，本地 versionCode 低于该值的用户被强制更新。
 */
object UpdateChecker {

    private const val TAG = "ZongYiBox"

    /** 解析后的更新清单 */
    data class Release(
        val versionCode: Int,
        val versionName: String,
        val changelog: String,
        val apkUrls: List<String>,
        val sizeBytes: Long,
        val sha256: String,
        val forceBelow: Int,
        val publishedAt: String,
    )

    /** 清单请求：整体 12s 上限，避免候选镜像逐个超时把启动拖住 */
    private val manifestHttp = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .dns(SmartDns)
        .retryOnConnectionFailure(true)
        .build()

    /** 安装包下载：不设 callTimeout（大文件），读超时放宽 */
    private val downloadHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .dns(SmartDns)
        .retryOnConnectionFailure(true)
        .build()

    // ---------- 检查 ----------

    /** 拉取最新版本清单；未配置更新源或全部镜像失败返回 null */
    suspend fun fetch(): Release? = withContext(Dispatchers.IO) {
        // Gitee raw 走 CDN 缓存，先带时间戳破缓存，失败再退回原始地址（缓存命中率更高）
        val candidates = UpdateConfig.manifestUrls.flatMap { listOf(withCacheBuster(it), it) }
        for (url in candidates) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    // 防止中间链路返回陈旧副本
                    .header("Cache-Control", "no-cache")
                    .header("User-Agent", "ZongYiBox-Update")
                    .build()
                manifestHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val body = resp.body?.string() ?: error("空响应")
                    val release = parse(body, url) ?: error("清单解析失败")
                    Log.i(TAG, "更新清单命中: $url（v${release.versionName}）")
                    return@withContext release
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.w(TAG, "更新清单拉取失败: $url", e)
            }
        }
        null
    }

    /** 追加时间戳参数，绕过 Gitee raw / CDN 的清单缓存 */
    private fun withCacheBuster(url: String): String {
        val sep = if (url.contains('?')) "&" else "?"
        return "$url${sep}_t=${System.currentTimeMillis()}"
    }

    /**
     * 解析清单。相对 apk 路径按清单所在目录展开，
     * 并为 GitHub 直链补充镜像地址（国内网络兜底）。
     */
    fun parse(json: String, manifestUrl: String): Release? = runCatching {
        val o = JsonParser.parseString(json).asJsonObject
        val code = o.int("versionCode") ?: return null
        val base = manifestUrl.substringBeforeLast('/') + "/"

        val declared = buildList {
            o.str("apk").takeIf { it.isNotBlank() }?.let { add(it) }
            o.get("apkUrls")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { e ->
                if (!e.isJsonNull) e.asString.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }

        Release(
            versionCode = code,
            versionName = o.str("versionName").ifBlank { "$code" },
            changelog = o.str("changelog"),
            apkUrls = declared.flatMap { withMirrors(if (it.startsWith("http")) it else base + it) }
                .distinct(),
            sizeBytes = o.int("size")?.toLong() ?: 0L,
            sha256 = o.str("sha256"),
            forceBelow = o.int("forceBelow") ?: 0,
            publishedAt = o.str("publishedAt"),
        )
    }.getOrNull()

    /** 取字符串字段，缺失/类型不符时返回空串 */
    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    /** 取整数字段，缺失/类型不符时返回 null（versionCode 缺失即视为无效清单） */
    private fun JsonObject.int(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }

    /** GitHub 直链补一份国内加速镜像，其余地址原样返回 */
    private fun withMirrors(url: String): List<String> {
        val mirrors = mutableListOf(url)
        if (url.startsWith("https://github.com/") || url.startsWith("https://objects.githubusercontent.com/")) {
            mirrors += "https://ghproxy.net/$url"
            mirrors += "https://gh-proxy.com/$url"
        }
        return mirrors
    }

    // ---------- 版本比较（纯函数，便于单测） ----------

    /** 远程版本是否比本地新；versionCode 优先，缺失时退回 versionName 数值比较 */
    fun isNewer(remote: Release, localCode: Int, localName: String): Boolean =
        if (remote.versionCode > 0) remote.versionCode > localCode
        else compareVersionName(remote.versionName, localName) > 0

    /** 本地版本是否已低于强制更新下限 */
    fun isForceUpdate(remote: Release, localCode: Int): Boolean =
        remote.forceBelow > 0 && localCode < remote.forceBelow

    /** 按点分数字段比较版本号，"1.10" > "1.9"；非数字前缀（如 v1.2 的 v）忽略 */
    fun compareVersionName(a: String, b: String): Int {
        fun parts(s: String) = s.split('.', '-', '_', '+').map { seg ->
            seg.dropWhile { !it.isDigit() }.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = (pa.getOrNull(i) ?: 0).compareTo(pb.getOrNull(i) ?: 0)
            if (d != 0) return d
        }
        return 0
    }

    // ---------- 下载 ----------

    /** 本地是否已有下载完成的安装包（重复点击「立即更新」时免重复下载） */
    fun cachedApk(ctx: Context, release: Release): File? {
        val f = apkFile(ctx, release)
        if (!f.exists()) return null
        if (release.sizeBytes > 0 && f.length() != release.sizeBytes) return null
        if (release.sha256.isNotBlank() && !sha256(f).equals(release.sha256, ignoreCase = true)) return null
        return f
    }

    private fun apkFile(ctx: Context, release: Release): File =
        File(File(ctx.cacheDir, "update"), "zongyibox-${release.versionName}.apk")

    /**
     * 下载安装包到 cacheDir/update/，多镜像自动重试。
     * @param onProgress (已下载字节, 总字节)，总字节未知时为 0
     */
    suspend fun download(
        ctx: Context,
        release: Release,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        val target = apkFile(ctx, release)
        val tmp = File(dir, "download.part")
        var lastError: Exception? = null

        for (url in release.apkUrls) {
            try {
                tmp.delete()
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "ZongYiBox-Update")
                    .build()
                downloadHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val body = resp.body ?: error("空响应")
                    val total = body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
                    body.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastTick = 0L
                            while (true) {
                                if (!isActive) throw CancellationException("下载已取消")
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val now = System.currentTimeMillis()
                                if (now - lastTick >= 120) {
                                    lastTick = now
                                    onProgress(done, total)
                                }
                            }
                            out.flush()
                        }
                    }
                }

                if (release.sha256.isNotBlank() && !sha256(tmp).equals(release.sha256, ignoreCase = true)) {
                    error("安装包校验失败，可能下载不完整")
                }
                if (release.sizeBytes > 0 && tmp.length() != release.sizeBytes) {
                    error("安装包大小不符（${tmp.length()}/${release.sizeBytes}）")
                }

                target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                onProgress(target.length(), target.length())
                Log.i(TAG, "更新包下载完成: ${target.absolutePath}")
                return@withContext target
            } catch (ce: CancellationException) {
                tmp.delete()
                throw ce
            } catch (e: Exception) {
                Log.w(TAG, "更新包下载失败: $url", e)
                lastError = e
                tmp.delete()
            }
        }
        throw lastError ?: IllegalStateException("没有可用的下载地址")
    }

    private fun sha256(file: File): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    /** 人类可读体积，用于弹窗展示 */
    fun humanSize(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }
}
