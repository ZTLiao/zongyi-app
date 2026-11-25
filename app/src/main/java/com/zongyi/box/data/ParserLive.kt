package com.zongyi.box.data

import android.util.Base64
import com.zongyi.jsoup.Jsoup
import org.json.JSONObject
import java.net.URI

/**
 * 88看球 HTML / 线路 JSON 解析（纯函数，不联网）。
 *
 * 页面结构（2026-11 抓取）：
 * - 赛程：li.live-list-group-time 为日期分组，li.group-game-item 为一场比赛
 * - 线路：GET /live/{id}/source → {"data":"<6位前缀><base64><2位后缀>"}
 * - 录像：/replay 列表 li.list-group-item div.media；详情页 .playback-content 内链
 *   均为 play.88player.top/m3u8.html?url=<m3u8 直链> 包装页
 */
object ParserLive {

    private val dateRegex = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val liveIdRegex = Regex("/live/(\\d+)/play")
    private val replayIdRegex = Regex("/replay/(\\d+)")

    /** 地址发布页中与本站同 CMS 的镜像域名（排除 popozhibo 等不兼容站点与 APP 下载站） */
    private val mirrorHostRegex = Regex("^(?:[a-z0-9-]+\\.)*88kanqiu\\.[a-z]{2,6}$")

    fun fixUrl(src: String): String {
        val s = src.trim()
        return when {
            s.isBlank() -> ""
            s.startsWith("http") -> s
            s.startsWith("//") -> "https:$s"
            s.startsWith("/") -> LiveSite.base + s
            else -> LiveSite.base + "/" + s
        }
    }

    /**
     * 解析官方地址发布页（https://www.88kq.me/）中的镜像站点。
     * 只保留域名形如 www.88kanqiu.xx 的地址（同 CMS，可复用全部解析规则）；
     * popozhibo.* 等不同模板站点、APP 下载引导链接一律丢弃。
     */
    fun parseMirrorBases(html: String): List<String> {
        val out = LinkedHashSet<String>()
        Jsoup.parse(html).select("a[href]").forEach { a ->
            val href = a.attr("href").trim()
            if (href.isBlank()) return@forEach
            val normalized = if (href.startsWith("http://") || href.startsWith("https://")) {
                href
            } else {
                "https://$href"
            }
            val uri = runCatching { URI(normalized) }.getOrNull() ?: return@forEach
            val host = uri.host?.lowercase() ?: return@forEach
            if (!mirrorHostRegex.matches(host)) return@forEach
            val scheme = (uri.scheme ?: "https").lowercase()
            if (scheme != "http" && scheme != "https") return@forEach
            out.add("$scheme://$host")
        }
        return out.toList()
    }

    /** 首页赛程：全部联赛按日期分组（站点一次返回约 5 天） */
    fun parseHome(html: String): LiveHome {
        val doc = Jsoup.parse(html, LiveSite.base)
        val days = LinkedHashMap<String, MutableList<LiveMatch>>()
        var currentDate = ""
        val seen = HashSet<Int>()

        doc.select("li.list-group-item").forEach { li ->
            if (li.hasClass("live-list-group-time")) {
                val text = li.text()
                dateRegex.find(text)?.let { currentDate = it.value }
                return@forEach
            }
            if (!li.hasClass("group-game-item")) return@forEach
            val match = parseMatch(li, currentDate) ?: return@forEach
            if (!seen.add(match.id)) return@forEach
            days.getOrPut(match.date) { mutableListOf() }.add(match)
        }

        return LiveHome(
            days.entries
                .filter { it.value.isNotEmpty() }
                .map { LiveDay(it.key, it.value.sortedBy { m -> m.time }) }
        )
    }

    private fun parseMatch(li: com.zongyi.jsoup.nodes.Element, date: String): LiveMatch? {
        val payBtn = li.selectFirst(".pay-btn")
        val anchor = li.selectFirst("div.pay-btn a") ?: li.selectFirst("a[href*='/live/']")
        val href = anchor?.attr("href").orEmpty()
        val id = payBtn?.attr("data-id")?.toIntOrNull()
            ?: li.selectFirst("[data-id]")?.attr("data-id")?.toIntOrNull()
            ?: liveIdRegex.find(href)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null

        val names = li.select(".team-name")
        val home = names.getOrNull(0)?.let { it.attr("title").ifBlank { it.text() }.trim() }.orEmpty()
        val away = names.getOrNull(1)?.let { it.attr("title").ifBlank { it.text() }.trim() }.orEmpty()
        if (home.isBlank() || away.isBlank()) return null

        val logos = li.select("img")
        val homeLogo = teamLogo(logos.getOrNull(0))
        val awayLogo = teamLogo(logos.getOrNull(1))

        // 桌面模板：.category-game-time / .game-type
        // 移动模板：.game-time，联赛名是其前一个兄弟节点
        val timeEl = li.selectFirst(".category-game-time") ?: li.selectFirst(".game-time")
        val time = timeEl?.text()?.trim().orEmpty()
        val league = li.selectFirst(".game-type")?.text()?.trim().orEmpty()
            .ifBlank { timeEl?.previousElementSibling()?.text()?.trim().orEmpty() }

        return LiveMatch(
            id = id,
            date = date,
            time = time,
            league = league,
            home = home,
            away = away,
            homeLogo = homeLogo,
            awayLogo = awayLogo,
            status = payBtn?.text()?.trim().orEmpty(),
        )
    }

    private fun teamLogo(img: com.zongyi.jsoup.nodes.Element?): String {
        if (img == null) return ""
        val raw = img.attr("data-src").ifBlank { img.attr("src") }.trim()
        if (raw.isBlank() || raw.contains("default.png")) return ""
        return fixUrl(raw)
    }

    /** 录像列表 */
    fun parseReplayPage(html: String, page: Int): ReplayPage {
        val doc = Jsoup.parse(html, LiveSite.base)
        val items = mutableListOf<ReplayItem>()
        val seen = HashSet<Int>()

        doc.select("div.media").forEach { media ->
            // 桌面模板：标题内嵌 a；移动模板：a 包裹整个 li
            val ancestor = generateSequence(media.parent()) { it.parent() }
                .firstOrNull { it.tagName() == "a" && it.attr("href").contains("/replay/") }
            val a = media.selectFirst("h4 a")
                ?: media.selectFirst("a[href*='/replay/']")
                ?: ancestor
                ?: return@forEach
            val id = replayIdRegex.find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
            if (!seen.add(id)) return@forEach
            val title = (media.selectFirst("h4 a") ?: media.selectFirst("h4"))?.text()?.trim()
                .orEmpty().ifBlank { a.text().trim() }
            if (title.isBlank()) return@forEach
            items.add(
                ReplayItem(
                    id = id,
                    title = title,
                    desc = media.selectFirst(".playback-desc")?.text()?.trim().orEmpty(),
                    cover = media.selectFirst("img")?.attr("src")?.let { fixUrl(it) }.orEmpty(),
                    publishedAt = media.select("span").lastOrNull()?.text()?.trim().orEmpty(),
                )
            )
        }

        val totalPages = doc.select(".pagination a.page-link")
            .mapNotNull { Regex("[?&]page=(\\d+)").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: page

        return ReplayPage(items, page, maxOf(page, totalPages))
    }

    /** 录像详情：取出全部可直接播放的 m3u8 线路 */
    fun parseReplayDetail(html: String, id: Int): ReplayDetail {
        val doc = Jsoup.parse(html, LiveSite.base)
        val title = doc.selectFirst(".playback-detail-title")?.text()?.trim()
            ?: doc.title().substringBefore(" - ").trim()
        val desc = doc.selectFirst("meta[name=description]")?.attr("content").orEmpty().trim()
        val cover = doc.selectFirst(".playback-content img")?.attr("src")?.let { fixUrl(it) }.orEmpty()

        val links = LinkedHashMap<String, LiveLink>()
        // 详情页把线路写成 <p class="playback-content"><p><a …，<p> 不允许嵌套，
        // 浏览器/解析器会自动闭合外层 <p>，a 就成了容器的兄弟节点；
        // 因此不能限定在容器内查找，改为全页扫描再按可播地址过滤。
        doc.select("a[href]").forEach { a ->
            val playable = resolvePlayable(a.attr("href")) ?: return@forEach
            val name = a.text().trim().ifBlank { "线路 ${links.size + 1}" }
            links.putIfAbsent(playable.first, LiveLink(name, playable.first, playable.second))
        }
        return ReplayDetail(id, title, desc, cover, links.values.toList())
    }

    /**
     * 线路接口 JSON 解码：
     * {"data":"PaSFpA<base64>pQ"} → Base64(JSON{links:[{name,url}]})
     */
    fun decodeSourceJson(json: String): List<LiveLink> {
        val payload = runCatching {
            val data = JSONObject(json).optString("data")
            if (data.isBlank()) {
                json
            } else {
                // 前缀 6 位、后缀 2 位为站点混淆位（实测 PaSFpA…pQ），解不出时退化为整段解码
                val body = if (data.length > 10) data.substring(6, data.length - 2) else data
                decodeBase64(body) ?: decodeBase64(data.substring(6)) ?: "{}"
            }
        }.getOrElse { json }

        val links = LinkedHashMap<String, LiveLink>()
        runCatching {
            val arr = JSONObject(payload).optJSONArray("links") ?: return@runCatching
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val playable = resolvePlayable(o.optString("url")) ?: continue
                val name = o.optString("name").trim().ifBlank { "线路 ${links.size + 1}" }
                links.putIfAbsent(playable.first, LiveLink(name, playable.first, playable.second))
            }
        }
        return links.values.toList()
    }

    private fun decodeBase64(text: String): String? = runCatching {
        val bytes = Base64.decode(text, Base64.NO_WRAP)
        String(bytes, Charsets.UTF_8)
    }.getOrNull()

    /**
     * 把站点线路地址归一化为播放器可用的 m3u8 直链 + Referer：
     * - play.88player.top/m3u8.html?url=<编码后的直链> → 取出 url 参数
     * - 本身就是 m3u8 → 原样返回
     * - 第三方 HTML 播放页（需执行脚本/防盗链）→ 放弃，避免 WebView 与广告
     */
    fun resolvePlayable(raw: String): Pair<String, String>? {
        val url = raw.trim()
        if (url.isEmpty()) return null
        if (url.contains("88player.top", ignoreCase = true) &&
            url.contains("m3u8.html", ignoreCase = true)
        ) {
            val inner = queryParam(url, "url") ?: return null
            return normalizeM3u8(inner)?.let { it to "https://play.88player.top/" }
        }
        return normalizeM3u8(url)?.let { it to LiveSite.base + "/" }
    }

    /** 取查询参数：不依赖 android.net.Uri，便于纯 JVM 解析测试 */
    private fun queryParam(url: String, key: String): String? {
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isBlank()) return null
        val raw = query.split('&').firstOrNull { it.substringBefore('=') == key }
            ?.substringAfter('=', "")
            ?: return null
        return runCatching {
            java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
        }.getOrDefault(raw)
    }

    private fun normalizeM3u8(url: String): String? {
        var s = url.trim()
        if (s.startsWith("//")) s = "https:$s"
        if (!s.startsWith("http")) return null
        return if (s.contains(".m3u8", ignoreCase = true)) s else null
    }

    /** 线路地址里的 auth_key=<过期时间戳>-0-0-<签名>，站点按此限时授权 */
    private val authKeyRegex = Regex("[?&]auth_key=(\\d{10})")

    /** 线路的授权过期时间（秒）；无 auth_key 的线路返回 Long.MAX_VALUE */
    fun expireAt(url: String): Long =
        authKeyRegex.find(url)?.groupValues?.get(1)?.toLongOrNull() ?: Long.MAX_VALUE

    /**
     * 线路是否已被站点判定过期。
     * 站点会一次性下发多条同名线路（旧签名 + 新签名），旧的那条必 403，
     * 进入页面时先过滤掉，避免默认播到必然失败的一条。
     */
    fun isExpired(url: String, nowSec: Long = System.currentTimeMillis() / 1000): Boolean =
        expireAt(url) <= nowSec
}
