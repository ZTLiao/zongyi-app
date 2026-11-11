package com.zongyi.box

import com.zongyi.box.data.M3u8Filter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * m3u8 广告过滤回归测试。样例取自 2026-11-11 真实抓包：
 * LZ 线路播放列表被注入 "# ppvod-ad-injected v1" 广告块（博彩推广），
 * 广告分片位于 /20261003/84_9109598f/... 目录，正片分片为同目录相对地址。
 */
class M3u8FilterTest {

    private val baseUrl = "https://v.lzcdn28.com/20261110/18054_91fd082b/2000k/hls/index.m3u8"

    /** 构造模拟播放列表：正片分片 + 可选广告块 */
    private fun playlist(contentCount: Int, withAd: Boolean): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:3")
        appendLine("#EXT-X-TARGETDURATION:8")
        appendLine("#EXT-X-MEDIA-SEQUENCE:0")
        repeat(contentCount) { i ->
            if (withAd && i == contentCount / 2) {
                appendLine("#EXT-X-DISCONTINUITY")
                appendLine("# ppvod-ad-injected v1")
                appendLine("#EXTINF:4,")
                appendLine("/20261003/84_9109598f/2000k/hls/751808405d7000000.ts")
                appendLine("#EXTINF:4,")
                appendLine("/20261003/84_9109598f/2000k/hls/751808405d7000001.ts")
                appendLine("#EXT-X-DISCONTINUITY")
            }
            appendLine("#EXTINF:4.0,")
            appendLine("7ed192c0984000%03d.ts".format(i))
        }
        appendLine("#EXT-X-ENDLIST")
    }

    // ---------- 无广告：原样返回 ----------

    @Test
    fun cleanPlaylist_noAds_returnsNull() {
        val r = M3u8Filter.clean(playlist(20, withAd = false), baseUrl)
        assertNull(r)
    }

    @Test
    fun masterPlaylist_returnsNull() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=800000,RESOLUTION=1080x608
            2000k/hls/index.m3u8
        """.trimIndent()
        assertNull(M3u8Filter.clean(master, baseUrl))
    }

    @Test
    fun garbage_returnsNull() {
        assertNull(M3u8Filter.clean("not an m3u8", baseUrl))
        assertNull(M3u8Filter.clean("", baseUrl))
    }

    // ---------- 广告过滤 ----------

    @Test
    fun removesAdBlock_keepsAllContent() {
        val result = M3u8Filter.clean(playlist(10, withAd = true), baseUrl)!!
        assertEquals(2, result.removedSegments)

        val content = result.cleaned.lines().filter { it.endsWith(".ts") }
        // 10 个正片分片全部保留，2 个广告分片剔除
        assertEquals(10, content.size)
        assertTrue(content.all { it.contains("7ed192c0984000") && it.startsWith("https://") })
        // 广告分片已被绝对地址改写？不，广告分片应被剔除
        assertFalse(result.cleaned.contains("751808405d7000"))
        assertFalse(result.cleaned.contains("ppvod-ad"))
        // 广告引入的 DISCONTINUITY 应被剔除
        assertEquals(0, result.cleaned.lines().count { it == "#EXT-X-DISCONTINUITY" })
        // 头部标签与 ENDLIST 保留
        assertTrue(result.cleaned.startsWith("#EXTM3U"))
        assertTrue(result.cleaned.contains("#EXT-X-ENDLIST"))
    }

    @Test
    fun rewritesRelativeSegmentsToAbsolute() {
        val result = M3u8Filter.clean(playlist(6, withAd = false), baseUrl)
        assertNull(result) // 无广告不落盘
        // 有广告时才需要绝对地址改写，这里验证 resolveUri 本身
        val base = java.net.URI(baseUrl)
        assertEquals(
            "https://v.lzcdn28.com/20261110/18054_91fd082b/2000k/hls/abc.ts",
            M3u8Filter.resolveUri(base, "abc.ts")
        )
        assertEquals(
            "https://v.lzcdn28.com/20261110/18054_91fd082b/2000k/hls/abc.ts",
            M3u8Filter.resolveUri(base, "https://v.lzcdn28.com/20261110/18054_91fd082b/2000k/hls/abc.ts")
        )
    }

    @Test
    fun removesAdBlock_byAbsoluteDifferentHost() {
        // 广告分片为完整 URL 且 host 不同（无 ppvod 标记的变体也应被剔除）
        val text = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-TARGETDURATION:8")
            repeat(8) { i ->
                if (i == 4) {
                    appendLine("#EXTINF:4,")
                    appendLine("https://adcdn.example.com/promo/ad0001.ts")
                }
                appendLine("#EXTINF:4.0,")
                appendLine("seg000$i.ts")
            }
            appendLine("#EXT-X-ENDLIST")
        }
        val result = M3u8Filter.clean(text, baseUrl)!!
        assertEquals(1, result.removedSegments)
        assertFalse(result.cleaned.contains("adcdn.example.com"))
    }

    @Test
    fun keepsLegitDiscontinuityBetweenContent() {
        // 正片内部自带的 DISCONTINUITY（前后都是正片分片）应保留
        val text = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-TARGETDURATION:8")
            repeat(6) { i ->
                if (i == 3) appendLine("#EXT-X-DISCONTINUITY")
                appendLine("#EXTINF:4.0,")
                appendLine("seg000$i.ts")
            }
            appendLine("#EXT-X-ENDLIST")
        }
        val result = M3u8Filter.clean(text, baseUrl)
        assertNull(result) // 无广告，不动原列表
    }

    @Test
    fun realWorldSample_adBlockInMiddle() {
        // 还原真实抓包结构：正片 → D + 广告7片 + D → 正片
        val text = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-TARGETDURATION:8")
            repeat(75) { i ->
                if (i == 74) {
                    appendLine("#EXT-X-DISCONTINUITY")
                    appendLine("# ppvod-ad-injected v1")
                    repeat(7) { j ->
                        appendLine("#EXTINF:4,")
                        appendLine("/20261003/84_9109598f/2000k/hls/751808405d700000$j.ts")
                    }
                    appendLine("#EXT-X-DISCONTINUITY")
                }
                appendLine("#EXTINF:4.0,")
                appendLine("7ed192c0984000%03d.ts".format(i))
            }
            appendLine("#EXT-X-ENDLIST")
        }
        val result = M3u8Filter.clean(text, baseUrl)!!
        assertEquals(7, result.removedSegments)
        val content = result.cleaned.lines().filter { it.endsWith(".ts") }
        assertEquals(75, content.size)
        assertFalse(result.cleaned.contains("20261003"))
        // 去掉广告后正片时间线连续，残留 DISCONTINUITY 应为 0
        assertEquals(0, result.cleaned.lines().count { it == "#EXT-X-DISCONTINUITY" })
    }
}
