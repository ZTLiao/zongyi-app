package com.zongyi.box.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Base64

/**
 * HTML 解析器。所有逻辑基于 docs/recon.md 侦察结论。
 * 纯函数、无网络，可单测。
 */
object Parser {

    /** 综艺盒子当前生效域名（跟随 Src.JUZI，故障切换时自动变化） */
    val BASE: String get() = Src.JUZI.base
    private val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    fun fixUrl(src: String): String = when {
        src.startsWith("http") -> src.replaceFirst("^(//)".toRegex(), "https://")
        src.startsWith("//") -> "https:$src"
        src.startsWith("/") -> BASE + src
        else -> "$BASE/$src"
    }

    // ---------- URL 构造 ----------

    /** /vrshow/{cat}--{sort}-----{letter}--{page}--{year}.html 12槽位 */
    fun categoryUrl(cat: Int, sort: String = "", letter: String = "", page: Int = 1, year: String = ""): String {
        val fields = arrayOf(cat.toString(), "", sort, "", "", letter, "", "", page.toString(), "", "", year)
        return "$BASE/vrshow/" + fields.joinToString("-") + ".html"
    }

    fun searchUrl(kw: String, page: Int = 1): String =
        if (page <= 1)
            "$BASE/vrsearch/$kw-------------.html"
        else
            "$BASE/vrsearch/$kw----------$page---.html"   // 与 vrshow 同一套 12 槽位，页码在第 9 槽

    fun detailUrl(id: Int) = "$BASE/vrdetail/$id.html"
    fun playUrl(id: Int, sid: Int, nid: Int) = "$BASE/vrplay/$id-$sid-$nid.html"

    // ---------- 通用条目 ----------

    private fun parseVodItem(li: Element): VodItem? {
        val a = li.selectFirst("a.thumb") ?: return null
        val href = a.attr("href")
        val m = Regex("/vrdetail/(\\d+)\\.html").find(href) ?: return null
        val id = m.groupValues[1].toInt()
        val title = a.attr("title").ifBlank {
            li.selectFirst("h3.stitle")?.text().orEmpty()
        }
        if (title.isBlank()) return null
        val cover = li.selectFirst("div.quic")?.let {
            it.attr("data-original").ifBlank { it.attr("data-background") }
        }.orEmpty()
        val remark = li.selectFirst(".icon-br span.label")?.text().orEmpty()
        val actors = li.selectFirst("p.des")?.text()?.removePrefix("主演:")?.trim().orEmpty()
        return VodItem(id, title, fixUrl(cover), remark, actors)
    }

    private fun parseItems(doc: Document): List<VodItem> =
        doc.select("li.m-item").mapNotNull(::parseVodItem)

    /** 从 .navigation 解析最大页码（无分页块返回 1） */
    private fun parseTotalPages(doc: Document): Int {
        val last = doc.selectFirst(".navigation a:contains(尾页)") ?: return 1
        val m = Regex("/vrshow/.*?-(\\d+)---").find(last.attr("href"))
            ?: Regex("-(\\d+)---\\.html").find(last.attr("href"))
        return m?.groupValues?.get(1)?.toIntOrNull() ?: 1
    }

    // ---------- 首页 ----------

    fun parseHome(html: String): HomeData {
        val doc = Jsoup.parse(html)
        val banner = doc.select(".banner .swiper-slide").mapNotNull { slide ->
            val a = slide.selectFirst("a") ?: return@mapNotNull null
            val m = Regex("/vrdetail/(\\d+)\\.html").find(a.attr("href")) ?: return@mapNotNull null
            val img = slide.selectFirst("div.img")
            VodItem(
                id = m.groupValues[1].toInt(),
                title = a.attr("title").ifBlank { slide.selectFirst(".stitle p")?.text().orEmpty() },
                cover = fixUrl(img?.attr("data-background").orEmpty()),
                remark = ""
            )
        }
        val sections = doc.select("section.mod").mapNotNull { sec ->
            val name = sec.selectFirst(".mod-head-name")?.text()?.trim().orEmpty()
            if (name.isEmpty() || name == "评论") return@mapNotNull null
            val items = sec.select("li.m-item").mapNotNull(::parseVodItem)
            if (items.isEmpty()) null else Section(name, items)
        }
        return HomeData(banner, sections)
    }

    // ---------- 列表 / 搜索 ----------

    fun parsePage(html: String, page: Int): PageData {
        val doc = Jsoup.parse(html)
        val items = parseItems(doc)
        val total = if (items.isEmpty()) 1 else parseTotalPages(doc)
        return PageData(items, page, maxOf(page, total))
    }

    fun isSearchEmpty(html: String): Boolean = html.contains("暂无相关内容")

    // ---------- 详情 ----------

    fun parseDetail(html: String, id: Int): VodDetail {
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("h1.title")?.text().orEmpty()
        val cover = fixUrl(doc.selectFirst(".albumDetailImg img")?.attr("src").orEmpty())
        val intro = doc.selectFirst(".albumDetailIntroTxt")?.attr("data-content")
            ?: doc.selectFirst(".albumDetailIntroTxt")?.text().orEmpty()

        var alias = ""; var director = ""; var actors = ""; var status = ""
        doc.select("ul.desc li").forEach { li ->
            val t = li.text()
            when {
                t.startsWith("别名") -> alias = t.removePrefix("别名：").trim()
                t.startsWith("导演") -> director = li.text().removePrefix("导演：").trim()
                t.startsWith("主演") -> {
                    // 演员是 <a> 链接，用 nbsp 分隔；统一转成逗号分隔
                    val links = li.select("a").map { it.text().trim() }.filter { it.isNotEmpty() }
                    actors = if (links.isNotEmpty()) links.joinToString(",")
                    else li.text().removePrefix("主演：").trim()
                }
                t.contains("状态") -> status = t.substringAfter("状态:").trim()
            }
        }
        // 状态行可能直接是「状态:xxx 更新:yyy」
        if (status.contains("更新:")) status = status.substringBefore("更新:").trim()

        // 线路 + 分集
        val nameToSid = HashMap<String, Int>()
        doc.select(".m-detail-playfrom-list li").forEach { li ->
            val target = li.attr("data-target").removePrefix("m-detail-play-panel-")
            val name = li.selectFirst("a")?.attr("title")?.ifBlank { li.text() } ?: return@forEach
            nameToSid[name] = target.toIntOrNull() ?: return@forEach
        }
        val sources = doc.select(".m-detail-play-panel").mapNotNull { panel ->
            val sid = panel.id().removePrefix("m-detail-play-panel-").toIntOrNull() ?: return@mapNotNull null
            val eps = panel.select(".jjtv-content__playlist a").mapNotNull { a ->
                val em = Regex("/vrplay/\\d+-(\\d+)-(\\d+)\\.html").find(a.attr("href"))
                    ?: return@mapNotNull null
                Episode(nid = em.groupValues[2].toInt(), name = a.text().trim())
            }
            val name = nameToSid.entries.firstOrNull { it.value == sid }?.key ?: "线路$sid"
            Source(sid, name, eps)
        }
        return VodDetail(id, title, cover, remark = status, alias, director, actors, status, intro, sources)
    }

    // ---------- 播放页 ----------

    fun parsePlayerData(html: String): PlayerData? {
        val m = Regex("var\\s+player_aaaa\\s*=\\s*(\\{.*?\\})\\s*</script>", RegexOption.DOT_MATCHES_ALL)
            .find(html) ?: return null
        return runCatching {
            GsonHolder.gson.fromJson(m.groupValues[1], PlayerData::class.java)
        }.getOrNull()
    }

    /** MacCMS encrypt: 0=明文 1=base64 2=base64+反转 */
    fun decodeStreamUrl(raw: String, encrypt: Int): String = when (encrypt) {
        1 -> String(Base64.getDecoder().decode(raw)).trim()
        2 -> String(Base64.getDecoder().decode(raw)).trim().reversed()
        else -> raw
    }

    fun parsePlayPage(html: String): StreamInfo? {
        val pd = parsePlayerData(html) ?: return null
        val url = decodeStreamUrl(pd.url, pd.encrypt)
        if (url.isBlank() || !url.startsWith("http")) return null
        // 两站播放链接分别形如 /vrplay/ 与 /vodplay/
        fun nidOf(link: String): Int? =
            Regex("/(?:vr|vod)play/\\d+-\\d+-(\\d+)\\.html").find(link)?.groupValues?.get(1)?.toIntOrNull()
        return StreamInfo(
            url = url,
            from = pd.from,
            nid = 0,
            prevNid = pd.linkPre.takeIf { it.isNotBlank() }?.let(::nidOf),
            nextNid = pd.linkNext.takeIf { it.isNotBlank() }?.let(::nidOf)
        )
    }
}

internal object GsonHolder {
    val gson = com.google.gson.Gson()
}
