package com.zongyi.box

import com.zongyi.box.data.Parser55
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 555影视（www.5k35.top，mxone/MacCMS 标准模板）解析回归测试。
 * 夹具为 2026-11-11 抓取的真实页面快照（app/src/test/resources/fixtures/fivek_*.html）。
 */
class Parser55Test {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!.readBytes()
            .toString(Charsets.UTF_8)

    // ---------- 首页 ----------

    @Test
    fun parseHome_bannerAndSections() {
        val home = Parser55.parseHome(fixture("fivek_home.html"))
        assertTrue("banner 应有多个轮播", home.banner.size >= 3)
        val first = home.banner.first()
        assertEquals("555", first.src)
        assertTrue(first.title.isNotBlank())
        assertTrue(first.cover.startsWith("https://"))
        // 版块：追剧周表/本月最佳电影等（影视资讯无海报会被过滤）
        val names = home.sections.map { it.title }
        assertTrue(names.contains("追剧周表") || names.any { it.contains("最佳") })
        assertTrue(home.sections.all { it.items.isNotEmpty() })
        assertTrue(home.sections.all { sec -> sec.items.all { it.src == "555" } })
    }

    // ---------- 分类列表 ----------

    @Test
    fun parseTypeItems() {
        val page = Parser55.parsePage(fixture("fivek_type3.html"), 1)
        assertTrue("分类页应有 40+ 条目", page.items.size >= 40)
        assertEquals(1, page.totalPages) // 服务端无分页
        val first = page.items.first()
        assertEquals("现在就出发第四季", first.title)
        assertTrue(first.id > 0)
        assertTrue(first.cover.startsWith("https://"))
        assertTrue(first.remark.isNotBlank())
    }

    // ---------- 搜索 ----------

    @Test
    fun parseSearchResults() {
        val page = Parser55.parsePage(fixture("fivek_search.html"), 1)
        assertTrue(page.items.isNotEmpty())
        assertTrue(page.items.any { it.title.contains("跑男") })
    }

    @Test
    fun searchEmpty() {
        assertTrue(Parser55.isSearchEmpty("<html><div>暂无相关内容</div></html>"))
    }

    // ---------- 详情 ----------

    @Test
    fun parseDetail_withMultiSources() {
        val d = Parser55.parseDetail(fixture("fivek_detail.html"), 762436)
        assertEquals("在韩国成为房主的方法", d.title)
        assertTrue(d.cover.startsWith("https://"))
        assertTrue("导演应解析到", d.director.contains("任"))
        assertTrue("主演应解析到", d.actors.contains("河正宇") || d.actors.length > 3)
        assertTrue(d.intro.contains("房主") || d.intro.isNotBlank())
        // 多线路：555 该剧有 10+ 条线路
        assertTrue("应解析出多条线路，实际 ${d.sources.size}", d.sources.size >= 8)
        val s1 = d.source(1)
        assertNotNull(s1)
        assertEquals("极速蓝光-b", s1!!.name)
        assertEquals(12, s1.episodes.size)
        assertEquals("第01集", s1.episodes.first().name)
        // 每条线路的 sid 与集名正确
        assertTrue(d.sources.all { it.episodes.isNotEmpty() })
    }

    // ---------- 播放页 ----------

    @Test
    fun parsePlayPage_m3u8() {
        val info = Parser55.parsePlayPage(fixture("fivek_play.html"))
        assertNotNull(info)
        assertTrue(info!!.url.startsWith("https://"))
        assertTrue(info.url.endsWith(".m3u8"))
    }

    // ---------- URL 构造 ----------

    @Test
    fun urlBuilders() {
        assertEquals("https://www.5k35.top/voddetail/123.html", Parser55.detailUrl(123))
        assertEquals("https://www.5k35.top/vodplay/123-1-2.html", Parser55.playUrl(123, 1, 2))
        assertEquals("https://www.5k35.top/vodtype/3.html", Parser55.typeUrl(3))
        assertTrue(Parser55.searchUrl("abc").endsWith("/vodsearch/abc-------------.html"))
    }
}
