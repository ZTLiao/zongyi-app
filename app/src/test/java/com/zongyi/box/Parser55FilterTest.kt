package com.zongyi.box

import com.zongyi.box.data.Parser55
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 555 vodshow 精细筛选解析测试（fixture 为真实分类页快照） */
class Parser55FilterTest {

    private val html by lazy {
        javaClass.getResourceAsStream("/fixtures/fivek_show1.html")!!.readBytes().decodeToString()
    }

    @Test
    fun parseFilters_fiveGroups() {
        val groups = Parser55.parseFilters(html)
        // 剧情类型/地区/语言/年份/排序 共 5 组
        assertEquals(listOf("剧情", "地区", "语言", "年份", "排序"), groups.map { it.label })
        assertEquals(listOf(3, 1, 4, 11, 2), groups.map { it.slot })
    }

    @Test
    fun parseFilters_valuesAreDecoded() {
        val groups = Parser55.parseFilters(html)
        val cls = groups.first { it.label == "剧情" }
        // 中文类型从百分号编码解码为原文，可直接回填构造新 URL
        assertTrue(cls.options.any { it.label == "仙侠" && it.value == "仙侠" })
        assertTrue(cls.options.any { it.label == "Netflix" && it.value == "Netflix" })
        // 排序组的 value 是 time/hits/score（非中文 label）
        val sort = groups.first { it.label == "排序" }
        assertEquals(listOf("time", "hits", "score"), sort.options.map { it.value })
    }

    @Test
    fun parseFilters_excludesAllOption() {
        val groups = Parser55.parseFilters(html)
        groups.forEach { g ->
            assertTrue("组 ${g.label} 不应包含'全部'选项", g.options.none { it.label == "全部" })
        }
    }

    @Test
    fun vodshowUrl_slots() {
        val base = Parser55.vodshowUrl(1).substringAfter("/vodshow/")
        // 默认 12 槽全空：1-----------.html
        assertEquals("1-----------.html", base)
        // 分类+类型+语言+年份
        val u1 = Parser55.vodshowUrl(2, cls = "喜剧", lang = "国语", year = "2024").substringAfter("/vodshow/")
        assertEquals("2---喜剧-国语-------2024.html", u1)
        // 地区+排序
        val u2 = Parser55.vodshowUrl(1, area = "大陆", by = "time").substringAfter("/vodshow/")
        assertEquals("1-大陆-time---------.html", u2)
        // 页码在槽 10（第 10 个字段），页 1 不写页码
        val u3 = Parser55.vodshowUrl(3, page = 4).substringAfter("/vodshow/")
        assertEquals("3--------4---.html", u3)
        val u4 = Parser55.vodshowUrl(3, page = 1).substringAfter("/vodshow/")
        assertEquals("3-----------.html", u4)
    }

    @Test
    fun parsePage_totalPagesFromLastPageLink() {
        val page = Parser55.parsePage(html, 1)
        // fixture 分类页尾页为 4195
        assertEquals(4195, page.totalPages)
        assertTrue(page.items.isNotEmpty())
    }
}
