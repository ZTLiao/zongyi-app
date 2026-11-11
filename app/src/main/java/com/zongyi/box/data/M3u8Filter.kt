package com.zongyi.box.data

import java.net.URI

/**
 * m3u8 播放列表广告过滤器。
 *
 * 站点 CDN 会在正片分片中插入博彩/推广广告块（实测样例带 "# ppvod-ad-injected v1"
 * 标记、由 #EXT-X-DISCONTINUITY 包裹，广告分片来自完全不同的目录甚至不同域名）。
 *
 * 过滤原理：统计播放列表里所有分片的"来源指纹"（host + 目录前缀），
 * 占多数的是正片，与其不一致的判定为广告分片，整块剔除；
 * 广告块两侧由广告引入的 #EXT-X-DISCONTINUITY 一并剔除，
 * 正片自身的 discontinuity 保留。
 * 剔除后相对地址统一改写为绝对地址，方便把过滤后的播放列表
 * 落盘为本地文件直接交给播放器播放。
 */
object M3u8Filter {

    /** 过滤结果：cleaned 为过滤后的播放列表全文 */
    data class Result(val cleaned: String, val removedSegments: Int) {
        val hadAds get() = removedSegments > 0
    }

    /** 一个分片块：块前是否有 DISCONTINUITY + 块内标签（EXTINF 等）+ 分片地址 */
    private class Block(val discBefore: Boolean) {
        val tags = mutableListOf<String>()
        var uri: String = ""
        var identity: String? = null
        val isAd get() = identity != null || isAdKeyword(uri)
    }

    /**
     * 清理媒体播放列表。
     * @return null 表示无需过滤（无广告 / master 列表 / 非法内容），调用方可直接播放原始地址
     */
    fun clean(text: String, baseUrl: String): Result? {
        val lines = text.lines()
        // master 播放列表（含码率档位）不在此处理
        if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) return null
        if (!lines.any { it.startsWith("#EXTM3U") }) return null

        val base = runCatching { URI(baseUrl) }.getOrNull() ?: return null

        // ---------- 解析：头部标签 + 分片块 ----------
        val head = mutableListOf<String>()
        val blocks = mutableListOf<Block>()
        val tail = mutableListOf<String>() // 最后一个分片之后的标签（如 EXT-X-ENDLIST）
        var cur: Block? = null
        var discPending = false

        for (raw in lines) {
            val line = raw.trim()
            when {
                line.isEmpty() -> continue
                line.startsWith("#EXT-X-DISCONTINUITY") && !line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE") ->
                    discPending = true

                line.startsWith("#") -> {
                    if (isAdComment(line)) continue // 广告标记注释行直接丢弃
                    val b = cur
                    if (b == null) {
                        // 还没遇到第一个分片：属于头部（或尾部）
                        if (blocks.isEmpty()) head.add(line) else tail.add(line)
                    } else {
                        // 该分片之后的标签：EXT-X-ENDLIST 属尾部，其余（KEY/MAP 等）挂当前块
                        if (line == "#EXT-X-ENDLIST") tail.add(line) else b.tags.add(line)
                    }
                }

                else -> {
                    val b = Block(discPending)
                    b.uri = line
                    b.identity = identityOf(base, line)
                    blocks.add(b)
                    cur = b
                    discPending = false
                }
            }
        }

        if (blocks.size < 4) return null

        // ---------- 判定正片身份（多数派） ----------
        val counts = blocks.groupingBy { it.identity ?: "?" }.eachCount()
        val majority = counts.maxByOrNull { it.value }?.key ?: return null

        // 指纹缺失(identity==null)的分片无法判断，保持原样（不算广告）
        val removedIdx = blocks.mapIndexedNotNull { i, b ->
            if (b.uri.isNotBlank() && b.identity != null && b.identity != majority) i else null
        }
        if (removedIdx.isEmpty()) return null

        // ---------- 重组 ----------
        val removedSet = removedIdx.toHashSet()
        val out = StringBuilder()
        head.forEach { out.appendLine(it) }

        var lastKeptOriginalIdx = -1
        blocks.forEachIndexed { i, b ->
            if (i in removedSet) return@forEachIndexed
            // 块前 DISCONTINUITY 仅当上一保留块与本块原始相邻时保留（广告引入的成对 discontinuity 被丢弃）
            if (b.discBefore && lastKeptOriginalIdx == i - 1) out.appendLine("#EXT-X-DISCONTINUITY")
            b.tags.forEach { out.appendLine(it) }
            out.appendLine(resolveUri(base, b.uri) ?: b.uri)
            lastKeptOriginalIdx = i
        }
        tail.forEach { out.appendLine(it) }

        return Result(out.toString(), removedSet.size)
    }

    private fun isAdComment(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("ad-injected") || lower.contains("ppvod-ad")
    }

    /** 分片地址本身带广告特征 */
    private fun isAdKeyword(uriLine: String): Boolean {
        val lower = uriLine.lowercase()
        return lower.contains("ppvod-ad") || lower.contains("/ad/") ||
            lower.contains("/promo/") || lower.contains("/gg/")
    }

    /** 分片来源指纹：host + 目录前 4 层路径。区分正片目录与广告目录足够，又不误杀正片 */
    private fun identityOf(base: URI, uriLine: String): String? {
        val resolved = resolveUri(base, uriLine) ?: return null
        return runCatching {
            val u = URI(resolved)
            val host = u.host ?: return null
            val segs = u.path?.split('/')?.filter { it.isNotBlank() } ?: emptyList()
            val dir = segs.dropLast(1).take(4).joinToString("/")
            "$host/$dir"
        }.getOrNull()
    }

    /** 相对地址 → 绝对地址（基于播放列表 URL）；已是绝对地址则原样返回 */
    fun resolveUri(base: URI, uriLine: String): String? {
        return try {
            if (uriLine.startsWith("http://") || uriLine.startsWith("https://")) uriLine
            else base.resolve(uriLine.trim()).toString()
        } catch (e: Exception) {
            null
        }
    }
}
