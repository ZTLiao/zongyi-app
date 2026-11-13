package com.zongyi.box

import com.zongyi.box.data.ParserTouhaos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 蓝光影院（touhaos.top，myui 模板）解析回归测试。
 * 夹具为 2026-11-14 抓取的真实页面快照（fixtures/site_touhaos/t_list/t_detail/t_play/dytt）。
 */
class ParserTouhaosTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!.readBytes()
            .toString(Charsets.UTF_8)

    // ---------- 首页 ----------

    @Test
    fun parseHome_sections() {
        val home = ParserTouhaos.parseHome(fixture("site_touhaos.html"))
        val names = home.sections.map { it.title }
        assertTrue("应有最新电影/最新电视剧等版块: $names", names.contains("最新电影"))
        assertTrue(home.sections.size >= 3)
        assertTrue(home.sections.all { it.items.isNotEmpty() })
        val first = home.sections.first().items.first()
        assertEquals("tbl", first.src)
        assertTrue(first.id > 0)
        assertTrue(first.title.isNotBlank())
    }

    // ---------- 分类列表 ----------

    @Test
    fun parsePage_itemsAndPagination() {
        val page = ParserTouhaos.parsePage(fixture("t_list.html"), 1)
        assertTrue("分类页应有 40+ 条目", page.items.size >= 40)
        assertTrue("分页应取到站点暴露的最大页", page.totalPages > 2)
        val first = page.items.first()
        assertTrue(first.id > 0)
        assertTrue(first.cover.startsWith("http"))
        assertEquals("tbl", first.src)
    }

    @Test
    fun categoryUrl_pagination() {
        assertEquals("https://touhaos.top/list/?3.html", ParserTouhaos.categoryUrl(3, 1))
        assertEquals("https://touhaos.top/list/?3-5.html", ParserTouhaos.categoryUrl(3, 5))
    }

    // ---------- 详情 ----------

    @Test
    fun parseDetail() {
        val d = ParserTouhaos.parseDetail(fixture("t_detail.html"), 10389)
        assertEquals("伏击", d.title)
        assertTrue("封面应来自图床", d.cover.startsWith("http"))
        assertTrue("remark=正片", d.remark.isNotBlank())
        assertTrue(d.actors.isNotBlank())
        assertTrue(d.director.isNotBlank())
        assertTrue("年份应解析到", d.status.isNotBlank())
        assertTrue("简介应取到正文", d.intro.length > 10 && !d.intro.contains("为广大网友收集"))
        // 线路：极速云(sid0)/电影天堂云(sid1)/dbm3u8(sid2)
        assertTrue("应有 3 条线路，实际 ${d.sources.map { it.name }}", d.sources.size >= 3)
        val names = d.sources.map { it.name }
        assertTrue(names.contains("极速云"))
        assertTrue(names.contains("dbm3u8"))
        d.source(0)?.let {
            assertEquals("极速云", it.name)
            assertTrue(it.episodes.isNotEmpty())
            assertEquals(0, it.episodes.first().nid)
        }
    }

    // ---------- 播放链路 ----------

    @Test
    fun parsePlayShareUrl() {
        val url = ParserTouhaos.parsePlayShareUrl(fixture("t_play.html"))
        assertEquals("https://vip.dytt-kan.com/share/731309c4bb223491a9f67eac5214fb2e", url)
    }

    @Test
    fun parseShareM3u8() {
        val shareUrl = "https://vip.dytt-kan.com/share/731309c4bb223491a9f67eac5214fb2e"
        val m3u8 = ParserTouhaos.parseShareM3u8(shareUrl, fixture("dytt.html"))
        assertEquals(
            "https://vip.dytt-kan.com/20250804/3210_731309c4/index.m3u8?sign=90bad7e0542e131673890e14babe3901",
            m3u8,
        )
    }

    @Test
    fun playUrl() {
        assertEquals("https://touhaos.top/video/?10389-1-0.html", ParserTouhaos.playUrl(10389, 1, 0))
    }

    // ---------- 搜索（站点拦截） ----------

    @Test
    fun searchAlwaysEmpty() {
        assertTrue(ParserTouhaos.isSearchEmpty(fixture("t_play.html")))
    }
}
