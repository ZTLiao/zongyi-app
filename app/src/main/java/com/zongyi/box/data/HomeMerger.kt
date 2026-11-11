package com.zongyi.box.data

/**
 * 首页双源内容聚合器：把综艺盒子 + 555影视的首页数据混合成统一的杂志式流。
 *
 * 设计原则：
 * - 不暴露来源，按主题与热度组织内容
 * - Banner 两源交错精选，构成统一推荐位
 * - 首个版块「今日热播」两源交错混排，作为开屏焦点
 * - 其余版块按主题交替排列，标题统一美化
 * - 同名内容全页去重（两站重复上架的剧只保留一个）
 * - 每个横滑版块限制条数，保证节奏一致
 */
object HomeMerger {

    /** 每个横滑版块的最大条数 */
    private const val SECTION_LIMIT = 12

    /** 首屏混合版块的最大条数 */
    private const val HERO_LIMIT = 12

    /** 版块标题美化：去除站点痕迹、统一命名风格 */
    fun prettifyTitle(raw: String): String {
        var t = raw.trim()
        t = t.replace(Regex("^555\\s*[·.:：]?\\s*"), "")
        t = t.replace(Regex("每周热门(.+?)排行$"), "热门$1")
        t = t.replace(Regex("^热门(.+?)(排行|榜)$"), "热门$1")
        val map = mapOf(
            "追剧周表" to "追剧日历",
            "本月最佳电影" to "本月佳片",
            "555独家专题" to "独家专题",
            "更新" to "最新上线",
            "预告、花絮" to "预告 · 花絮",
            "今日更新" to "今日更新",
        )
        return map[t] ?: t
    }

    /** 标题归一化：用于跨站同名去重（忽略空格/标点差异） */
    private fun normalizeTitle(t: String): String =
        t.replace(Regex("[\\s·．.。:：,，、!！?？~～()（）\\[\\]【】]"), "").lowercase()

    /** 两列表交错合并（1:1 轮流，尾部多余追加） */
    private fun <T> interleave(a: List<T>, b: List<T>): List<T> {
        val out = mutableListOf<T>()
        val max = maxOf(a.size, b.size)
        for (i in 0 until max) {
            if (i < a.size) out.add(a[i])
            if (i < b.size) out.add(b[i])
        }
        return out
    }

    /**
     * 合并两源首页数据。
     * @return null 表示两源全部失败
     */
    fun mergeHome(jz: HomeData?, dy: HomeData?): HomeData? {
        if (jz == null && dy == null) return null
        // 单源可用时另一源视为空列表，走同一套合并逻辑，保证结构一致（同样有「今日热播」首屏）
        return mergeCore(
            jz ?: HomeData(emptyList(), emptyList()),
            dy ?: HomeData(emptyList(), emptyList())
        )
    }

    private fun mergeCore(jz: HomeData, dy: HomeData): HomeData {

        // ---------- 全页去重：banner、首屏、各版块共用一个 seen 集合 ----------
        val seen = mutableSetOf<String>()

        // ---------- Banner：两源交错精选 ----------
        val banner = interleave(jz.banner, dy.banner)
            .filter { seen.add(normalizeTitle(it.title)) }
            .take(5)

        // ---------- 版块：交替排列 + 美化标题 ----------
        data class SrcSection(val title: String, val items: List<VodItem>)
        val jzSecs = jz.sections.map { SrcSection(prettifyTitle(it.title), it.items) }
        val dySecs = dy.sections.map { SrcSection(prettifyTitle(it.title), it.items) }

        val sections = mutableListOf<Section>()

        fun addSection(title: String, items: List<VodItem>, limit: Int = SECTION_LIMIT, min: Int = 3) {
            val clean = items
                .filter { seen.add(normalizeTitle(it.title)) }
                .take(limit)
            if (clean.size >= min) sections.add(Section(title, clean))
        }

        // 首屏焦点：两源各版块头部内容交错混排（最低 2 条即展示，保证单源小数据时也有首屏焦点）
        val heroPool = interleave(
            jzSecs.flatMap { it.items.take(2) },
            dySecs.flatMap { it.items.take(2) }
        )
        addSection("今日热播", heroPool, HERO_LIMIT, min = 2)

        // 其余版块：两源轮流出现（跳过每版块前 2 条已进首屏的部分？不，重看体验保留完整列表，
        // 由跨版块去重保证首屏出现过的剧不再重复出现）
        var i = 0
        var j = 0
        while (i < jzSecs.size || j < dySecs.size) {
            if (i < jzSecs.size) { addSection(jzSecs[i].title, jzSecs[i].items); i++ }
            if (j < dySecs.size) { addSection(dySecs[j].title, dySecs[j].items); j++ }
        }

        // 版块为空时兜底（理论上 banner 存在即有内容）
        return HomeData(banner, sections)
    }
}
