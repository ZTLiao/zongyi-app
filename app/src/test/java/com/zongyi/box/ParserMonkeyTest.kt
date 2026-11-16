package com.zongyi.box

import com.zongyi.box.data.ParserMonkey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 猴影工坊（monkey-flix.com，Astro SSR）解析回归测试。
 * 夹具为 2026-11-14 抓取的真实页面快照（fixtures/site_monkeyflix/mf_s1/mf_browse/mf_detail）。
 */
class ParserMonkeyTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!.readBytes()
            .toString(Charsets.UTF_8)

    // ---------- 首页 ----------

    @Test
    fun parseHome_sections() {
        val home = ParserMonkey.parseHome(fixture("site_monkeyflix.html"))
        assertTrue("应有多个版块", home.sections.size >= 5)
        val names = home.sections.map { it.title }
        assertTrue(names.contains("热门流行榜单"))
        assertTrue(home.sections.all { it.items.isNotEmpty() })
        val item = home.sections.first().items.first()
        assertEquals("mf", item.src)
        assertTrue(item.id > 0 && item.title.isNotBlank() && item.cover.startsWith("http"))
    }

    // ---------- 分类 / 搜索 ----------

    @Test
    fun parseBrowsePage() {
        val page = ParserMonkey.parsePage(fixture("mf_browse.html"), 1)
        assertTrue("浏览页应有 20 条", page.items.size >= 15)
        assertEquals("存在下一页 → 总页数+1", 2, page.totalPages)
        assertTrue(page.items.all { it.src == "mf" })
        assertTrue(page.items.all { it.cover.startsWith("http") })
    }

    @Test
    fun parseSearchResults() {
        val page = ParserMonkey.parsePage(fixture("mf_s1.html"), 1)
        assertTrue("搜索应有结果", page.items.isNotEmpty())
        assertTrue(page.items.any { it.title == "奔跑吧 合伙人" })
    }

    @Test
    fun searchEmptyDetection() {
        assertFalse(ParserMonkey.isSearchEmpty(fixture("mf_s1.html")))
        assertTrue(ParserMonkey.isSearchEmpty("<html><body>没有找到相关内容</body></html>"))
    }

    // ---------- 分类筛选 URL 组合 ----------

    @Test
    fun browseUrl_noFilter() {
        assertEquals("https://monkey-flix.com/browse?type=3", ParserMonkey.browseUrl(3))
    }

    @Test
    fun browseUrl_allFilters() {
        assertEquals(
            "https://monkey-flix.com/browse?type=3&genre=25&year=2026&order_by=vod_year&page=2",
            ParserMonkey.browseUrl(3, page = 2, genre = "25", year = "2026", orderBy = "vod_year")
        )
    }

    @Test
    fun browseUrl_partialFilters_page1Omitted() {
        assertEquals(
            "https://monkey-flix.com/browse?type=1&order_by=vod_douban_score",
            ParserMonkey.browseUrl(1, genre = "", year = " ", orderBy = "vod_douban_score")
        )
    }

    // ---------- 详情 ----------

    @Test
    fun parseDetail() {
        val d = ParserMonkey.parseDetail(fixture("mf_detail.html"), 65919)
        assertEquals("还有明天2023", d.title)
        assertTrue(d.cover.startsWith("http"))
        assertEquals("HD中字", d.remark)
        assertTrue(d.actors.contains("宝拉·柯特莱西"))
        assertTrue(d.director.contains("宝拉·柯特莱西"))
        assertTrue(d.status.contains("2023"))
        assertTrue("简介应为纯文本", d.intro.contains("1946") && !d.intro.contains("<"))
        assertTrue("应有线路", d.sources.isNotEmpty())
        val line = d.sources.first()
        assertTrue(line.episodes.isNotEmpty())
        val ep = line.episodes.first()
        assertEquals("HD中字", ep.name)
        assertTrue("分集应带直链", ep.url.startsWith("https://"))
    }

    // ---------- vod_play_url 解析 ----------

    @Test
    fun parsePlayUrl_single() {
        val lines = ParserMonkey.parsePlayUrl("HD中字\$https://a.com/x.m3u8")
        assertEquals(1, lines.size)
        assertEquals(1, lines[0].episodes.size)
        assertEquals("HD中字", lines[0].episodes[0].name)
        assertEquals("https://a.com/x.m3u8", lines[0].episodes[0].url)
    }

    @Test
    fun parsePlayUrl_multi() {
        val raw = "HD\$https://a.com/1.m3u8#第2集\$https://a.com/2.m3u8#第3集\$https://a.com/3.m3u8" +
            "\$\$\$\"蓝光\"\$https://b.com/1.m3u8#\"蓝光\"02\$https://b.com/2.m3u8"
        val lines = ParserMonkey.parsePlayUrl(raw)
        assertEquals(2, lines.size)
        assertEquals(3, lines[0].episodes.size)
        assertEquals(2, lines[1].episodes.size)
        assertEquals(1, lines[1].episodes[1].nid)
        assertEquals("https://b.com/2.m3u8", lines[1].episodes[1].url)
    }

    @Test
    fun propsJsonExtraction() {
        val json = ParserMonkey.extractPropsJson(fixture("mf_detail.html"))
        assertNotNull(json)
        assertTrue(json!!.toString().contains("vod_play_url"))
    }
}
