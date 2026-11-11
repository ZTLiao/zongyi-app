package com.zongyi.box

import com.zongyi.box.data.HomeData
import com.zongyi.box.data.HomeMerger
import com.zongyi.box.data.Section
import com.zongyi.box.data.VodItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 首页双源聚合逻辑测试 */
class HomeMergerTest {

    private fun item(id: Int, title: String, src: String = "jz") =
        VodItem(id, title, "https://img/$id.jpg", "更新至第1集", src = src)

    private fun home(vararg sections: Pair<String, List<VodItem>>, banner: List<VodItem> = emptyList()) =
        HomeData(banner, sections.map { Section(it.first, it.second) })

    @Test
    fun merge_bothNull_returnsNull() {
        assertNull(HomeMerger.mergeHome(null, null))
    }

    @Test
    fun merge_singleSource_stillWorks() {
        // 真实场景：单站各版块有 8+ 条内容，首屏抽走前 2 条后版块仍有内容
        val d = home("大陆综艺" to (1..8).map { item(it, "剧$it") })
        val merged = HomeMerger.mergeHome(d, null)!!
        assertTrue(merged.sections.any { it.title == "今日热播" })
        assertTrue(merged.sections.any { it.title == "大陆综艺" })
    }

    @Test
    fun merge_interleavesBanners() {
        val jz = home("a" to listOf(item(1, "甲"), item(2, "乙"), item(3, "丙")),
            banner = listOf(item(1, "甲"), item(4, "丁")))
        val dy = home("b" to listOf(item(5, "戊"), item(6, "己"), item(7, "庚")),
            banner = listOf(item(5, "戊"), item(8, "辛")))
        val merged = HomeMerger.mergeHome(jz, dy)!!
        // banner 交错：甲、戊、丁、辛，共 4 个
        assertEquals(4, merged.banner.size)
        assertEquals(listOf("甲", "戊", "丁", "辛"), merged.banner.map { it.title })
    }

    @Test
    fun merge_heroFirst_andSectionsAlternate() {
        // 每版块 6 条：首屏抽走前 2 条后仍剩 4 条（≥3），版块不会被清空丢弃
        val jz = home(
            "大陆综艺" to listOf(item(1, "甲"), item(2, "乙"), item(3, "丙"), item(4, "丁"), item(41, "丁2"), item(42, "丁3")),
            "港台综艺" to listOf(item(11, "子"), item(12, "丑"), item(13, "寅"), item(14, "卯"), item(141, "卯2"), item(142, "卯3"))
        )
        val dy = home(
            "热门连续剧" to listOf(item(5, "戊"), item(6, "己"), item(7, "庚"), item(8, "辛"), item(81, "辛2"), item(82, "辛3")),
            "热门动漫" to listOf(item(15, "辰"), item(16, "巳"), item(17, "午"), item(18, "未"), item(181, "未2"), item(182, "未3"))
        )
        val merged = HomeMerger.mergeHome(jz, dy)!!

        // 首版块是"今日热播"
        assertEquals("今日热播", merged.sections.first().title)
        val hero = merged.sections.first().items
        // 混排：两源各版块前 2 条交错，去重后 12 条上限
        assertTrue(hero.size <= 12)
        val heroTitles = hero.map { it.title }
        assertTrue(heroTitles.containsAll(listOf("甲", "戊", "子", "辰")))

        // 首屏出现过的内容不再重复出现在后续版块
        val rest = merged.sections.drop(1)
        assertEquals(listOf("大陆综艺", "热门连续剧", "港台综艺", "热门动漫"), rest.map { it.title })
        val restTitles = rest.flatMap { it.items }.map { it.title }
        heroTitles.forEach { assertFalse("首屏内容不应重复: $it", restTitles.contains(it)) }
    }

    @Test
    fun merge_dedupesCrossSiteSameTitle() {
        // 两站都上架《现在就出发第四季》，只保留一个
        val jz = home("大陆综艺" to listOf(item(1, "现在就出发 第四季"), item(2, "乙"), item(3, "丙")))
        val dy = home(
            "热门综艺" to listOf(item(9, "现在就出发第四季"), item(6, "己"), item(7, "庚")),
            banner = listOf(item(9, "现在就出发第四季"))
        )
        val merged = HomeMerger.mergeHome(jz, dy)!!
        val all = merged.sections.flatMap { it.items } + merged.banner
        assertEquals(1, all.count { it.title.contains("现在就出发") })
    }

    @Test
    fun merge_sectionsLimitedTo12() {
        val items = (1..30).map { item(it, "剧$it") }
        val jz = home("大陆综艺" to items)
        val dyItems = (1..30).map { item(it + 100, "美剧$it", "555") }
        val dy = home("热门连续剧" to dyItems)
        val merged = HomeMerger.mergeHome(jz, dy)!!
        // 每版块 ≤12（今日热播也是版块）
        assertTrue(merged.sections.all { it.items.size <= 12 })
    }

    @Test
    fun prettify_titles() {
        assertEquals("追剧日历", HomeMerger.prettifyTitle("追剧周表"))
        assertEquals("本月佳片", HomeMerger.prettifyTitle("本月最佳电影"))
        assertEquals("独家专题", HomeMerger.prettifyTitle("555独家专题"))
        assertEquals("热门连续剧", HomeMerger.prettifyTitle("每周热门连续剧排行"))
        assertEquals("热门综艺纪录", HomeMerger.prettifyTitle("每周热门综艺纪录排行"))
        assertEquals("最新上线", HomeMerger.prettifyTitle("更新"))
        assertEquals("预告 · 花絮", HomeMerger.prettifyTitle("预告、花絮"))
        assertEquals("大陆综艺", HomeMerger.prettifyTitle("大陆综艺"))
        assertEquals("大陆综艺", HomeMerger.prettifyTitle("555 · 大陆综艺"))
    }

    @Test
    fun merge_tinySectionsDropped() {
        // 条目 <3 的版块被丢弃，避免出现贫瘠版块
        val jz = home("瘦版块" to listOf(item(1, "甲"), item(2, "乙")))
        val dy = home("热门连续剧" to listOf(item(5, "戊"), item(6, "己"), item(7, "庚")))
        val merged = HomeMerger.mergeHome(jz, dy)!!
        assertFalse(merged.sections.any { it.title == "瘦版块" })
        assertNotNull(merged.sections.firstOrNull { it.title == "今日热播" })
    }
}
