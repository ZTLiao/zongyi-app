package com.zongyi.box

import com.zongyi.box.data.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：夹具为 2026-11-10 抓取的真实页面快照（app/src/test/resources/fixtures）。
 * 站点模板变更时，先更新夹具再跑这里。
 */
class ParserTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!.readBytes()
            .toString(Charsets.UTF_8)

    // ---------- 首页 ----------

    @Test
    fun parseHome_bannerAndSections() {
        val home = Parser.parseHome(fixture("home.html"))
        assertEquals(3, home.banner.size)
        assertTrue(home.banner[0].cover.startsWith("https://"))
        assertTrue(home.sections.isNotEmpty())
        val names = home.sections.map { it.title }
        assertTrue(names.contains("大陆综艺"))
        assertTrue(names.contains("港台综艺"))
        assertTrue(names.contains("日韩综艺"))
        assertTrue(names.contains("欧美综艺"))
        // 每个版块有条目且封面是绝对地址
        home.sections.forEach { sec ->
            assertTrue("版块「${sec.title}」无条目", sec.items.isNotEmpty())
            sec.items.forEach {
                assertTrue("封面未拼接域名: ${it.cover}", it.cover.startsWith("https://"))
                assertTrue(it.id > 0)
            }
        }
    }

    // ---------- 分类列表 ----------

    @Test
    fun parseCategoryPage() {
        val page = Parser.parsePage(fixture("show1.html"), 1)
        assertTrue(page.items.size >= 10)
        assertEquals(1, page.page)
        assertTrue("应解析出总页数>300, 实际=${page.totalPages}", page.totalPages > 300)
    }

    @Test
    fun parseSearchResults() {
        val page = Parser.parsePage(fixture("s2.html"), 1)
        assertTrue(page.items.isNotEmpty())
        assertTrue(page.items.any { it.title.contains("奔跑") })
    }

    // ---------- URL 构造 ----------

    @Test
    fun categoryUrl_matchesSlots() {
        // 12 槽位：cat-..-sort-....-letter-..-page-..-year
        assertEquals(
            "https://m.ijujizy.com/vrshow/1--time------3---2026.html",
            Parser.categoryUrl(1, "time", "", 3, "2026")
        )
        assertEquals(
            "https://m.ijujizy.com/vrshow/1--------2---.html",
            Parser.categoryUrl(1, "", "", 2, "")
        )
        assertEquals(
            "https://m.ijujizy.com/vrshow/1-----A---1---.html",
            Parser.categoryUrl(1, "", "A", 1, "")
        )
    }

    @Test
    fun searchUrl_format() {
        assertEquals(
            "https://m.ijujizy.com/vrsearch/%E5%A5%94%E8%B7%91-------------.html",
            Parser.searchUrl("%E5%A5%94%E8%B7%91", 1)
        )
        assertEquals(
            "https://m.ijujizy.com/vrsearch/%E5%A5%94%E8%B7%91----------2---.html",
            Parser.searchUrl("%E5%A5%94%E8%B7%91", 2)
        )
    }

    // ---------- 详情页 ----------

    @Test
    fun parseDetail_full() {
        val d = Parser.parseDetail(fixture("detail.html"), 16852)
        assertEquals("夏日的星星", d.title)
        assertTrue(d.cover.startsWith("https://"))
        assertTrue("简介 data-content 应被提取", d.intro.contains("旅途启程"))
        assertEquals("代露娃,李亭哲,徐不气,翟佳宁", d.actors)
        assertEquals("应有 13 条线路", 13, d.sources.size)
        val lz = d.source(1)!!
        assertEquals("LZ在线", lz.name)
        assertEquals(1, lz.episodes.size)
        assertEquals("第01集", lz.episodes[0].name)
        assertEquals(1, lz.episodes[0].nid)
        val ff = d.source(12)!!
        assertEquals("FF在线", ff.name)
        assertEquals(1, ff.episodes[0].nid)
    }

    // ---------- 播放页 ----------

    @Test
    fun parsePlay_lz() {
        val info = Parser.parsePlayPage(fixture("play1.html"))
        assertTrue(info != null)
        assertTrue("LZ线路应为 lzcdn 域名", info!!.url.contains("lzcdn"))
        assertTrue(info.url.endsWith(".m3u8"))
    }

    @Test
    fun parsePlay_ff() {
        val info = Parser.parsePlayPage(fixture("play12.html"))
        assertTrue(info != null)
        assertTrue(info!!.url.contains("ffzy"))
        assertEquals("ffm3u8", info.from)
    }

    // ---------- 加密兼容 ----------

    @Test
    fun decodeEncrypt() {
        val raw = "https://v.example.com/a/index.m3u8"
        val b64 = java.util.Base64.getEncoder().encodeToString(raw.toByteArray())
        assertEquals(raw, Parser.decodeStreamUrl(raw, 0))
        assertEquals(raw, Parser.decodeStreamUrl(b64, 1))
        assertEquals(raw, Parser.decodeStreamUrl(
            java.util.Base64.getEncoder().encodeToString(raw.reversed().toByteArray()), 2
        ))
    }
}
