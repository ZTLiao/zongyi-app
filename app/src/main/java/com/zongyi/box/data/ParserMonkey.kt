package com.zongyi.box.data

import com.zongyi.jsoup.Jsoup
import com.zongyi.jsoup.nodes.Document
import com.zongyi.jsoup.nodes.Element
import org.json.JSONObject

/**
 * 猴影工坊（monkey-flix.com，Astro SSR）解析器。
 * 纯函数、无网络，可单测（fixtures: site_monkeyflix/mf_s1/mf_browse/mf_detail）。
 *
 * 站点结构（侦察结论）：
 * - 首页/搜索/浏览页：纯 HTML 渲染，条目 a[href="/watch/{id}"][aria-label=标题]，
 *   内含 img[src=封面]；首页版块在 astro-carousel 内，标题 h3.text-2xl
 * - 分类页：/browse?type={1电影片|2连续剧|3综艺片|4动漫片}&page={n}，翻页"下一页"链接存在即有下一页
 * - 搜索：/search?q={kw}（单页约 20 条）
 * - 详情页：/watch/{id}，数据在 <astro-island props="...">（HTML 转义的 JSON）里，
 *   MacCMS 字段：vod_id/vod_name/vod_pic/vod_remarks/vod_actor/vod_director/vod_content，
 *   vod_play_url 为 [0, "集名$m3u8"]（多线路 $$$ 分组、集 # 分隔、名称$地址）
 * - 播放：地址直接在详情数据中，无需播放页
 */
object ParserMonkey {

    private val BASE: String get() = Src.MF.base

    // ---------- URL 构造 ----------

    fun browseUrl(
        cat: Int, page: Int = 1,
        genre: String = "", year: String = "", orderBy: String = "",
    ): String {
        val sb = StringBuilder("$BASE/browse?type=$cat")
        if (genre.isNotBlank()) sb.append("&genre=$genre")
        if (year.isNotBlank()) sb.append("&year=$year")
        if (orderBy.isNotBlank()) sb.append("&order_by=$orderBy")
        if (page > 1) sb.append("&page=$page")
        return sb.toString()
    }

    fun searchUrl(kw: String): String = "$BASE/search?q=$kw"

    fun detailUrl(id: Int) = "$BASE/watch/$id"

    /** 分类 id → 展示名 */
    val categories = listOf("电影片" to 1, "连续剧" to 2, "综艺片" to 3, "动漫片" to 4)

    /**
     * 各分类下的类型子分类（站点导航 genre 参数，2026-11 侦察）。
     * key = browseUrl 的 type 值
     */
    val genresByType: Map<Int, List<Pair<String, Int>>> = mapOf(
        1 to listOf(
            "动作片" to 6, "喜剧片" to 7, "爱情片" to 8, "科幻片" to 9,
            "恐怖片" to 10, "剧情片" to 11, "战争片" to 12, "记录片" to 20, "伦理片" to 34,
        ),
        2 to listOf(
            "国产剧" to 13, "香港剧" to 14, "韩国剧" to 15, "欧美剧" to 16,
            "台湾剧" to 21, "日本剧" to 22, "海外剧" to 23, "泰国剧" to 24, "短剧" to 36,
        ),
        3 to listOf("大陆综艺" to 25, "港台综艺" to 26, "日韩综艺" to 27, "欧美综艺" to 28),
        4 to listOf("国产动漫" to 29, "日韩动漫" to 30, "欧美动漫" to 31, "港台动漫" to 32, "海外动漫" to 33),
    )

    /** 年代筛选（year 参数，全部=""） */
    val yearsMf = listOf(
        "2026" to "2026", "2020年代" to "2020_2026", "2010年代" to "2010_2019",
        "2000年代" to "2000_2009", "90年代" to "1990_1999", "80年代" to "1980_1989",
        "70年代" to "1970_1979", "60年代" to "1960_1969", "更早" to "1871_1959",
    )

    /** 排序（order_by 参数，综合排序=""） */
    val ordersMf = listOf("首映时间" to "vod_year", "高分优先" to "vod_douban_score")

    // ---------- 通用条目 ----------

    private fun parseItem(a: Element): VodItem? {
        val href = a.attr("href")
        val m = Regex("/watch/(\\d+)").find(href) ?: return null
        val id = m.groupValues[1].toInt()
        val title = a.attr("aria-label").ifBlank { a.selectFirst("h4")?.text().orEmpty() }.trim()
        if (title.isBlank()) return null
        val cover = a.selectFirst("img")?.attr("src").orEmpty()
        return VodItem(id, title, cover, "", src = Src.MF.key)
    }

    private fun parseItems(doc: Document): List<VodItem> =
        doc.select("a[href^=\"/watch/\"]").mapNotNull(::parseItem)

    // ---------- 首页 ----------

    fun parseHome(html: String): HomeData {
        val doc = Jsoup.parse(html)
        val sections = doc.select("astro-carousel").mapNotNull { carousel ->
            val name = carousel.selectFirst("h3")?.text()?.trim().orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            val items = carousel.select("a[href^=\"/watch/\"]").mapNotNull(::parseItem)
                .distinctBy { it.id }.take(20)
            if (items.isEmpty()) null else Section(name, items)
        }
        // 兜底：无 carousel 结构时用页面上的 watch 卡片组一版
        if (sections.isEmpty()) {
            val items = parseItems(doc).distinctBy { it.id }.take(30)
            if (items.isNotEmpty()) return HomeData(emptyList(), listOf(Section("推荐", items)))
            return HomeData(emptyList(), emptyList())
        }
        val banner = sections.firstOrNull()?.items?.take(4).orEmpty()
        return HomeData(banner, sections)
    }

    // ---------- 分类 / 搜索列表 ----------

    fun parsePage(html: String, page: Int): PageData {
        val doc = Jsoup.parse(html)
        val items = parseItems(doc).distinctBy { it.id }
        // "下一页"链接存在 → 有下一页（站点不暴露总页数，按 +1 递增驱动无限滚动）
        val hasNext = doc.select("a[rel=next], a:containsOwn(下一页)").isNotEmpty()
        val total = if (hasNext && items.isNotEmpty()) page + 1 else page
        return PageData(items, page, total)
    }

    fun isSearchEmpty(html: String): Boolean {
        if (html.contains("没有找到")) return true
        return parseItems(Jsoup.parse(html)).isEmpty()
    }

    // ---------- 详情 ----------

    /** 详情页 → astro-island props JSON（HTML 反转义，取嵌套的 movie 节点） */
    fun extractPropsJson(html: String): JSONObject? {
        val m = Regex("<astro-island[^>]*props=\"([^\"]+)\"").find(html) ?: return null
        val raw = m.groupValues[1]
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
        return try {
            val root = JSONObject(raw)
            // 结构：{"movie":[0,{...vod 字段...}]}
            root.optJSONArray("movie")?.optJSONObject(1) ?: root
        } catch (_: Exception) {
            null
        }
    }

    /** MacCMS vod_play_url 解析：$$$ 分线路、# 分集、集名$地址 */
    fun parsePlayUrl(raw: String): List<Source> =
        raw.split("\$\$\$").mapIndexed { sid, line ->
            val eps = line.split("#").mapIndexedNotNull { nid, ep ->
                val idx = ep.indexOf('$')
                if (idx <= 0) return@mapIndexedNotNull null
                val name = ep.substring(0, idx).trim()
                val url = ep.substring(idx + 1).trim()
                if (name.isEmpty() || url.isEmpty()) null else Episode(nid, name, url)
            }
            Source(sid, "线路${sid + 1}", eps)
        }.filter { it.episodes.isNotEmpty() }

    fun parseDetail(html: String, id: Int): VodDetail {
        val props = extractPropsJson(html)
            ?: return VodDetail(id, "", "", "")
        fun str(key: String): String =
            props.optJSONArray(key)?.optString(1).orEmpty().trim()
                .takeIf { it.isNotBlank() && it != "null" } ?: ""

        val title = str("vod_name")
        val cover = str("vod_pic")
        val remark = str("vod_remarks")
        val year = when (val y = props.optJSONArray("vod_year")?.opt(1)) {
            is Number -> y.toString()
            is String -> y
            else -> ""
        }
        // 内容是富文本（<p>/<br/>/&nbsp; 等），统一转纯文本
        val intro = Jsoup.parse(str("vod_content")).text().trim()
        val sources = parsePlayUrl(str("vod_play_url"))
        return VodDetail(
            id = id,
            title = title,
            cover = cover,
            remark = remark.ifBlank { year },
            alias = str("vod_class"),
            director = str("vod_director"),
            actors = str("vod_actor"),
            status = year,
            intro = intro,
            sources = sources,
        )
    }
}
