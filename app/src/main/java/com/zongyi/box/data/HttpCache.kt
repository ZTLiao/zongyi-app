package com.zongyi.box.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 页面 HTML 文件级缓存（cacheDir/http）。
 *
 * 策略：
 * - 网络请求【成功】时写入（getHtml 内联保存，已在 IO 线程）
 * - 网络请求【失败/超时】时回读历史缓存，保证界面有数据、App 不崩
 * - init 时清理 7 天前的过期缓存；总容量超限按最旧优先淘汰
 */
object HttpCache {

    private const val MAX_FILES = 60
    private const val TTL_MS = 7 * 24 * 3600 * 1000L

    private lateinit var dir: File

    fun init(context: Context) {
        dir = File(context.cacheDir, "http").apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - TTL_MS
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    private fun key(url: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 网络失败时回读历史缓存；无缓存返回 null（由调用方决定是否向上抛） */
    suspend fun load(url: String): String? = withContext(Dispatchers.IO) {
        if (!::dir.isInitialized) return@withContext null
        val f = File(dir, key(url))
        try {
            if (f.exists() && f.length() > 0) f.readText() else null
        } catch (_: Exception) {
            null
        }
    }

    /** 请求成功后写入（调用方保证已在 IO 线程），超限时按最旧淘汰 */
    fun save(url: String, html: String) {
        if (!::dir.isInitialized) return
        try {
            val files = (dir.listFiles() ?: return).toMutableList()
            files.sortBy { it.lastModified() }
            while (files.size >= MAX_FILES) files.removeAt(0).delete()
            File(dir, key(url)).writeText(html)
        } catch (_: Exception) {
            // 缓存写失败不影响主流程
        }
    }
}
