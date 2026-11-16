package com.zongyi.box.data

import com.zongyi.jsoup.Jsoup
import com.zongyi.jsoup.nodes.Document
import com.zongyi.jsoup.nodes.Element

/**
 * 蓝光影院（touhaos.top，myui 模板）解析器。
 * 纯函数、无网络，可单测（fixtures: site_touhaos/t_list/t_detail/t_play/dytt）。
 *
 * 站点结构（侦察结论）：
 * - 首页版块：div.myui-panel > h3.title + ul.myui-vodlist > a.myui-vodlist__thumb
 * - 分类页：/list/?{tid}.html，翻页 /list/?{tid}-{page}.html（page 从 2 起，第 1 页无 -page 后缀）
 * - 详情页：/detail/?{id}.html；封面 img[data-original]；线路 tab a[href="#playlistN"]，
 *   分集在各 playlistN 容器内，链接 /video/?{id}-{sid}-{nid}.html（sid 与 playlistN 无对应关系，以链接为准）
 * - 播放页：/video/?{id}-{sid}-{nid}.html 内 var now="https://<host>/share/<hash>"
 *   → 再请求 share 页 → const url = "/path/index.m3u8?sign=..." → 拼 share 页域名得直链
 * - 搜索：站点被安全验证拦截，不支持（搜索该源恒返回空）
 */
object ParserTouhaos {

    private val BASE: String get() = Src.TBL.base

    // ---------- URL 构造 ----------

    fun categoryUrl(cat: Int, page: Int = 1): String =
        if (page <= 1) "$BASE/list/?$cat.html" else "$BASE/list/?$cat-$page.html"

    fun detailUrl(id: Int) = "$BASE/detail/?$id.html"
    fun playUrl(id: Int, sid: Int, nid: Int) = "$BASE/video/?$id-$sid-$nid.html"

    // ---------- 通用条目 ----------

    private fun parseItem(a: Element): VodItem? {
        val href = a.attr("href")
        val m = Regex("/detail/\\?(\\d+)\\.html").find(href) ?: return null
        val id = m.groupValues[1].toInt()
        val title = a.attr("title").ifBlank { a.text() }.trim()
        if (title.isBlank()) return null
        val cover = a.attr("data-original").ifBlank { a.attr("src") }
        // a 自身可能是封面卡（列表页），也可能是标题链接（详情内嵌列表）——
        // 后者取兄弟容器里的封面
        val card = if (a.hasClass("myui-vodlist__thumb")) a
        else a.closest("div.myui-vodlist__box") ?: a
        val coverEl = if (a.hasClass("myui-vodlist__thumb")) a
        else card.selectFirst("a.myui-vodlist__thumb") ?: a
        val remark = coverEl.selectFirst(".pic-text")?.text()?.trim().orEmpty()
        val coverUrl = cover.ifBlank {
            coverEl.attr("data-original").ifBlank { coverEl.attr("src") }
        }
        return VodItem(id, title, fixUrl(coverUrl), remark, src = Src.TBL.key)
    }

    private fun fixUrl(u: String): String = when {
        u.startsWith("http") -> u
        u.startsWith("/") -> BASE + u
        else -> "$BASE/$u"
    }

    /** 条目集合：列表页（a.myui-vodlist__thumb 直挂）与详情页嵌套列表通用 */
    private fun parseItems(root: Element): List<VodItem> =
        root.select("a.myui-vodlist__thumb").mapNotNull(::parseItem)

    // ---------- 首页 ----------

    fun parseHome(html: String): HomeData {
        val doc = Jsoup.parse(html)
        val sections = doc.select("div.myui-panel").mapNotNull { panel ->
            val name = panel.selectFirst("h3.title")?.text()?.trim().orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            val items = parseItems(panel).take(20)
            if (items.isEmpty()) null else Section(name, items)
        }
        // 蓝光影院无独立 banner 区，用最新电影头部充作 banner 轮播
        val banner = sections.firstOrNull()?.items?.take(4).orEmpty()
        return HomeData(banner, sections)
    }

    // ---------- 分类列表 ----------

    fun parsePage(html: String, page: Int): PageData {
        val doc = Jsoup.parse(html)
        val items = parseItems(doc)
        val total = parseTotalPages(doc, page, items.isNotEmpty())
        return PageData(items, page, total)
    }

    /** 分页：所有 /list/?cat-N.html 链接取最大 N；无链接时按当前页封顶 */
    private fun parseTotalPages(doc: Document, page: Int, hasItems: Boolean): Int {
        var max = page
        val re = Regex("/list/\\?\\d+-(\\d+)\\.html")
        doc.select("a").forEach { a ->
            re.find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()?.let { if (it > max) max = it }
        }
        return if (hasItems) maxOf(page, max) else page
    }

    // ---------- 详情 ----------

    fun parseDetail(html: String, id: Int): VodDetail {
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("h1.title")?.text()?.trim().orEmpty()
        val thumb = doc.selectFirst(".myui-content__thumb .myui-vodlist__thumb")
        val thumbImg = thumb?.selectFirst("img")
        val cover = fixUrl(
            thumb?.attr("data-original")?.ifBlank { null }
                ?: thumbImg?.attr("data-original")?.ifBlank { null }
                ?: thumbImg?.attr("src").orEmpty()
        )
        val remark = thumb?.selectFirst(".pic-text")?.text()?.trim().orEmpty()

        // 元信息：分类/地区/年份挤在同一段（span+单 a 交替）；主演/导演一段多 a；简介段 ownText
        var year = ""; var area = ""; var cls = ""
        var director = ""; var actors = ""; var intro = ""
        doc.select("p.data").forEach { p ->
            val spans = p.select("span.text-muted")
            spans.forEach { span ->
                val label = span.text()
                val value = if (spans.size > 1) {
                    // 分类/地区/年份同段：每个 span 后紧跟它自己的一个 a
                    span.nextElementSibling()?.text()?.trim().orEmpty()
                } else {
                    // 主演/导演：段内全部 a 逗号连接
                    p.select("a").joinToString(",") { it.text().trim() }
                }
                when {
                    label.startsWith("分类") -> cls = value
                    label.startsWith("地区") -> area = value
                    label.startsWith("年份") -> year = value
                    label.startsWith("主演") -> actors = value
                    label.startsWith("导演") -> director = value
                    label.startsWith("简介") -> intro = p.ownText().trim()
                }
            }
        }
        if (intro.isBlank()) {
            intro = doc.selectFirst("meta[name=description]")?.attr("content").orEmpty()
        }

        val sources = parsePlaylists(doc)
        return VodDetail(
            id = id,
            title = title,
            cover = cover,
            remark = remark.ifBlank { listOfNotNull(cls, year, area).joinToString(" · ") },
            alias = cls,
            director = director,
            actors = actors,
            status = year,
            intro = intro,
            sources = sources,
        )
    }

    /**
     * 线路×分集：tab 名（a[href="#playlistN"]）+ 容器内分集链接 /video/?id-sid-nid.html。
     * sid 以链接为准（tab 顺序与 sid 无关）。
     */
    fun parsePlaylists(doc: Document): List<Source> {
        val tabNames = mutableMapOf<String, String>()
        doc.select("a[data-toggle=\"tab\"]").forEach { a ->
            val target = a.attr("href")   // #playlist3
            if (target.startsWith("#")) tabNames[target] = a.text().trim()
        }
        val bySid = LinkedHashMap<Int, MutableMap<Int, String>>()
        val lineNames = mutableMapOf<Int, String>()
        val epRe = Regex("/video/\\?(\\d+)-(\\d+)-(\\d+)\\.html")
        doc.select("div[id^=\"playlist\"]").forEach { div ->
            val divId = div.attr("id")            // playlist3
            val name = tabNames["#$divId"] ?: "线路$divId"
            div.select("a").forEach { a ->
                val m = epRe.find(a.attr("href")) ?: return@forEach
                val sid = m.groupValues[2].toInt()
                val nid = m.groupValues[3].toInt()
                val epName = a.attr("title").ifBlank { a.text() }.trim()
                bySid.getOrPut(sid) { LinkedHashMap() }[nid] = epName
                if (sid !in lineNames || lineNames[sid].isNullOrBlank()) lineNames[sid] = name
            }
        }
        return bySid.map { (sid, eps) ->
            Source(sid, lineNames[sid] ?: "线路$sid",
                eps.entries.sortedBy { it.key }.map { Episode(it.key, it.value) })
        }
    }

    // ---------- 播放链路 ----------

    /** 播放页 → share 页地址（var now="https://host/share/<hash>"） */
    fun parsePlayShareUrl(playPageHtml: String): String? =
        Regex("var\\s+now\\s*=\\s*\"([^\"]+)\"").find(playPageHtml)?.groupValues?.get(1)

    /** share 页 → m3u8 直链（const url = "/path/index.m3u8?sign=..."，拼 share 页域名） */
    fun parseShareM3u8(shareUrl: String, shareHtml: String): String? {
        val path = Regex("const\\s+url\\s*=\\s*\"([^\"]+)\"").find(shareHtml)?.groupValues?.get(1)
            ?: return null
        if (path.startsWith("http")) return path
        val base = java.net.URI(shareUrl)
        return "${base.scheme}://${base.host}$path"
    }

    /** 搜索被安全验证拦截：恒视为空 */
    fun isSearchEmpty(html: String): Boolean = true
}
