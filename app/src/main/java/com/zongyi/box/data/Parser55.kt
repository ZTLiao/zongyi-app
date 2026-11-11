package com.zongyi.box.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 555影视（www.5k35.top 等 mxone/MacCMS 标准模板）解析器。
 * 纯函数、无网络，配合 fixtures 可单测。
 *
 * URL 结构：
 * - 详情 /voddetail/{id}.html
 * - 播放 /vodplay/{id}-{sid}-{nid}.html
 * - 分类 /vodtype/{cat}.html（服务端无分页，单页约 48 条）
 * - 搜索 /vodsearch/{kw}-------------.html
 */
object Parser55 {

    fun fixUrl(src: String): String = when {
        src.startsWith("http") -> src.replaceFirst("^(//)".toRegex(), "https://")
        src.startsWith("//") -> "https:$src"
        src.startsWith("/") -> Src.DY555.base + src
        else -> "${Src.DY555.base}/$src"
    }

    fun detailUrl(id: Int) = "${Src.DY555.base}/voddetail/$id.html"
    fun playUrl(id: Int, sid: Int, nid: Int) = "${Src.DY555.base}/vodplay/$id-$sid-$nid.html"
    fun typeUrl(cat: Int) = "${Src.DY555.base}/vodtype/$cat.html"
    fun searchUrl(kw: String) = "${Src.DY555.base}/vodsearch/$kw-------------.html"

    /** 555 分类：电影/连续剧/综艺纪录/动漫（擦边短剧等不收录） */
    val categories = listOf("电影" to 1, "连续剧" to 2, "综艺纪录" to 3, "动漫" to 4)

    // ---------- vodshow 精细筛选（12 槽位）----------
    //
    // URL 结构：/vodshow/{cat}-{area}-{by}-{class}-{lang}-{x}-{x}-{x}-{page}-{x}-{year}.html
    //   槽1=分类(1电影/2连续剧/3综艺纪录/4动漫)
    //   槽2=地区(大陆/香港/美国/日本/韩国…)  槽3=排序(time/hits/score)
    //   槽4=类型(剧情/喜剧/科幻/Netflix…)    槽5=语言(国语/粤语/英语…)
    //   槽9=页码                             槽12=年份
    // 示例：/vodshow/1--time-科幻----2--2025.html = 电影+时间排序+科幻类型+第2页+2025年

    /** 筛选选项（value 直接回填到 vodshowUrl 对应槽位） */
    data class FilterOption(val label: String, val value: String)

    /** 筛选组：一组同一槽位的选项（如"地区"组的全部地区） */
    data class FilterGroup(val label: String, val slot: Int, val options: List<FilterOption>)

    /** 组标题 → URL 槽位（以站点页面实际链接为准的固定映射） */
    private val filterSlotByTitle = mapOf("剧情" to 3, "地区" to 1, "语言" to 4, "年份" to 11, "排序" to 2)

    fun vodshowUrl(
        cat: Int, area: String = "", by: String = "", cls: String = "",
        lang: String = "", year: String = "", page: Int = 1,
    ): String {
        val f = arrayOfNulls<String>(12)
        f[0] = cat.toString()
        f[1] = area
        f[2] = by
        f[3] = cls
        f[4] = lang
        if (page > 1) f[8] = page.toString()
        f[11] = year
        return "${Src.DY555.base}/vodshow/" + f.map { it ?: "" }.joinToString("-") + ".html"
    }

    /** 解析 /vodshow/ 页面的筛选区（5 组：剧情/地区/语言/年份/排序） */
    fun parseFilters(html: String): List<FilterGroup> {
        val doc = Jsoup.parse(html)
        val groups = mutableListOf<FilterGroup>()
        // 每个 module-class-item 是一组：title 与选项链接是兄弟节点
        doc.select("div.module-class-item").forEach { box ->
            val title = box.selectFirst(".module-item-title")?.text()?.trim() ?: return@forEach
            val slot = filterSlotByTitle[title] ?: return@forEach
            val opts = mutableListOf<FilterOption>()
            box.select("a[href*=/vodshow/]").forEach { a ->
                val label = a.attr("title").ifBlank { a.text() }.trim()
                if (label.isEmpty() || label == "全部") return@forEach
                val path = Regex("/vodshow/(.+)\\.html").find(a.attr("href"))?.groupValues?.get(1) ?: return@forEach
                val fields = path.split("-")
                if (fields.size < 12) return@forEach
                val raw = fields[slot]
                if (raw.isBlank()) return@forEach
                // href 里的中文是百分号编码，解码后可直接回填构造新 URL（OkHttp 会再编码）
                val value = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
                opts.add(FilterOption(label, value))
            }
            if (opts.isNotEmpty()) groups.add(FilterGroup(title, slot, opts))
        }
        return groups
    }

    // ---------- 通用条目 ----------

    /** mxone 模板列表条目：a.module-poster-item（海报卡）或 div.module-card-item（搜索/卡片行） */
    fun parseItem(el: Element): VodItem? {
        val a = if (el.tagName() == "a") el else el.selectFirst("a[href*=/voddetail/]") ?: return null
        val m = Regex("/voddetail/(\\d+)\\.html").find(a.attr("href")) ?: return null
        val id = m.groupValues[1].toInt()
        val title = (a.attr("title").ifBlank {
            el.selectFirst(".module-poster-item-title")?.text()
                ?: el.selectFirst(".module-card-item-title")?.text()
                ?: el.selectFirst(".module-card-item-title strong")?.text()
                ?: ""
        }).trim()
        if (title.isBlank()) return null
        val cover = fixUrl(el.selectFirst("img[data-original]")?.attr("data-original").orEmpty())
        val remark = el.selectFirst(".module-item-note")?.text()?.trim().orEmpty()
        val infos = el.select(".module-info-item-content")
        val actors = infos.getOrNull(1)?.text()?.trim().orEmpty()
        return VodItem(id, title, fixUrl(cover), remark, actors, Src.DY555.key)
    }

    private fun parseItems(doc: Document): List<VodItem> {
        val seen = LinkedHashSet<Int>()
        val out = mutableListOf<VodItem>()
        doc.select("a.module-poster-item, div.module-card-item").forEach { el ->
            val item = parseItem(el) ?: return@forEach
            if (seen.add(item.id)) out.add(item)
        }
        return out
    }

    // ---------- 首页 ----------

    fun parseHome(html: String): HomeData {
        val doc = Jsoup.parse(html)
        val banner = doc.select(".swiper-big .swiper-slide").mapNotNull { slide ->
            val a = slide.selectFirst("a[href*=/voddetail/]") ?: return@mapNotNull null
            val m = Regex("/voddetail/(\\d+)\\.html").find(a.attr("href")) ?: return@mapNotNull null
            val title = slide.selectFirst(".v-title span")?.text()?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            val cover = a.attr("data-bg")
            val remark = slide.selectFirst(".v-ins p")?.text()?.trim().orEmpty()
            VodItem(m.groupValues[1].toInt(), title, fixUrl(cover), remark, src = Src.DY555.key)
        }
        val sections = doc.select("div.module").mapNotNull { mod ->
            // h2 自身即 class="module-title"（mxone 模板），兼容其他变体
            val name = mod.selectFirst("h2.module-title, .module-title h2, .module-title-text")
                ?.text()?.trim().orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            val items = mod.select("a.module-poster-item, div.module-card-item")
                .mapNotNull(::parseItem).distinctBy { it.id }
            if (items.isEmpty()) null else Section(name, items)
        }
        return HomeData(banner, sections)
    }

    // ---------- 列表 / 搜索 ----------

    /** mxone 服务端无分页（分类页一次返回全部），totalPages 恒为 1 */
    fun parsePage(html: String, page: Int): PageData {
        val doc = Jsoup.parse(html)
        // vodshow 页有分页（尾页链接的槽 10 = 总页数）；vodtype 页无分页 → 1
        val totalPages = doc.selectFirst("a.page-link[title=\"尾页\"]")?.attr("href")?.let { href ->
            Regex("/vodshow/(.+)\\.html").find(href)?.groupValues?.get(1)
                ?.split("-")?.getOrNull(8)?.toIntOrNull()
        } ?: 1
        return PageData(parseItems(doc), page, maxOf(1, totalPages))
    }

    fun isSearchEmpty(html: String): Boolean {
        if (!html.contains("module-card-item")) return true
        return false
    }

    // ---------- 详情 ----------

    fun parseDetail(html: String, id: Int): VodDetail {
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst(".module-info-heading h1")?.text()?.trim().orEmpty()
        val cover = fixUrl(
            doc.selectFirst(".module-info-poster img[data-original]")?.attr("data-original")
                ?: doc.selectFirst(".module-info-poster img")?.attr("src").orEmpty()
        )
        // 标签行第一项是年份
        val tagLinks = doc.select(".module-info-tag-link")
        val year = tagLinks.firstOrNull()?.text()?.trim().orEmpty()

        var director = ""; var actors = ""; var lead = ""
        doc.select("div.module-info-item").forEach { it ->
            val label = it.selectFirst(".module-info-item-title")?.text()?.trim().orEmpty()
            val content = it.selectFirst(".module-info-item-content")?.text()?.trim().orEmpty()
            when {
                label.startsWith("导演") -> director = content.removeSuffix("/")
                label.startsWith("主演") -> actors = content.removeSuffix("/")
                label.startsWith("首播") || label.startsWith("上映") -> lead = content
            }
        }
        val intro = doc.selectFirst(".module-info-introduction-content")?.text()?.trim().orEmpty()
        val remark = doc.selectFirst(".module-item-note")?.text()?.trim().orEmpty()
        val status = listOf(year, lead).filter { it.isNotBlank() }.joinToString(" · ")
            .ifBlank { remark }

        // 线路：module-play-list 块；sid 从块内第一个 vodplay 链接推断，名称按 data-dropdown-value 顺序对应
        val tabNames = doc.select("div[data-dropdown-value]").map { it.attr("data-dropdown-value").trim() }
        var blockIdx = 0
        val sources = doc.select("div.module-play-list").mapNotNull { block ->
            val first = block.selectFirst("a[href*=/vodplay/]") ?: return@mapNotNull null
            val m = Regex("/vodplay/\\d+-(\\d+)-\\d+\\.html").find(first.attr("href"))
                ?: return@mapNotNull null
            val sid = m.groupValues[1].toInt()
            val name = tabNames.getOrNull(blockIdx)?.takeIf { it.isNotBlank() } ?: "线路$sid"
            blockIdx++
            val eps = block.select("a.module-play-list-link").mapNotNull { a ->
                val em = Regex("/vodplay/\\d+-\\d+-(\\d+)\\.html").find(a.attr("href"))
                    ?: return@mapNotNull null
                val label = a.selectFirst("span")?.text()?.trim()
                    ?: a.text().trim().ifBlank { "第${em.groupValues[1]}集" }
                Episode(em.groupValues[1].toInt(), label)
            }
            if (eps.isEmpty()) null else Source(sid, name, eps)
        }.distinctBy { it.sid }

        return VodDetail(id, title, cover, remark, "", director, actors, status, intro, sources)
    }

    // ---------- 播放页（player_aaaa 与综艺盒子同构，复用主解析器） ----------

    fun parsePlayPage(html: String): StreamInfo? = Parser.parsePlayPage(html)
}
