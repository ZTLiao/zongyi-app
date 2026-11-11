package com.zongyi.box.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 站点请求层。只发 GET、只读 HTML，不执行任何站点脚本（天然无广告/无第三方跳转）。
 * HTML 请求带候选域名故障切换（getHtml），m3u8/分片走 CDN 不参与切换。
 */
object SiteApi {

    private const val TAG = "ZongYiBox"

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        // 兜底整个调用（含 DNS 解析阶段）：DNS 挂起不受 connect/read 超时约束，
        // 曾出现搜索永久 loading 的卡死，callTimeout 保证请求最终一定结束
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private suspend fun getHtml(url: String): String = withContext(Dispatchers.IO) {
        // 匹配 URL 所属站源（按其全部候选域名匹配，兼容已切换的历史域名）
        val src = Src.entries.firstOrNull { s -> s.bases.any { url.startsWith(it) } }
        val matchedBase = src?.bases?.firstOrNull { url.startsWith(it) }

        // 尝试顺序：URL 原域名 → 该源其余候选域名（故障自动切换）
        data class Attempt(val url: String, val base: String, val validate: Boolean)
        val attempts = buildList {
            if (src == null || matchedBase == null) {
                add(Attempt(url, "", validate = false))
            } else {
                add(Attempt(url, matchedBase, validate = false))
                val path = url.substring(matchedBase.length)
                src.bases.filter { it != matchedBase }.forEach {
                    add(Attempt(it + path, it, validate = true))
                }
            }
        }

        var lastError: Exception? = null
        for (attempt in attempts) {
            try {
                val html = fetchHtmlOnce(attempt.url)
                // 候选域名须通过内容签名校验（防"APP 下载引导页"等假内容站）；
                // 当前域名维持原行为不校验，无回归风险
                if (attempt.validate && src != null && !src.markers.any { html.contains(it) }) {
                    throw IllegalStateException("候选域名响应非内容页: ${attempt.url}")
                }
                if (src != null && attempt.base != src.base) {
                    src.base = attempt.base
                    DomainStore.record(src)
                    Log.i(TAG, "${src.label} 已自动切换到域名: ${attempt.base}")
                }
                HttpCache.save(attempt.url, html)
                return@withContext html
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.w(TAG, "页面请求失败: ${attempt.url}", e)
                lastError = e
            }
        }
        // 全部域名失败：回读历史缓存，保证界面有数据、App 不崩
        val cached = HttpCache.load(url)
        if (cached != null) cached else throw (lastError ?: IllegalStateException("加载失败: $url"))
    }

    private fun fetchHtmlOnce(url: String): String {
        val builder = Request.Builder().url(url)
        BrowserHeaders.document().forEach { (name, value) -> builder.header(name, value) }
        return http.newCall(builder.build()).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}: $url" }
            resp.body?.string() ?: throw IllegalStateException("空响应: $url")
        }
    }

    /** 首页（按来源） */
    suspend fun home(src: Src): HomeData = when (src) {
        Src.JUZI -> Parser.parseHome(getHtml(Parser.BASE + "/"))
        Src.DY555 -> Parser55.parseHome(getHtml(Src.DY555.base + "/"))
    }

    /** 分类列表（按来源） */
    suspend fun category(src: Src, cat: Int, sort: String, year: String, page: Int): PageData =
        when (src) {
            Src.JUZI -> Parser.parsePage(getHtml(Parser.categoryUrl(cat, sort, "", page, year)), page)
            Src.DY555 -> Parser55.parsePage(getHtml(Parser55.typeUrl(cat)), page)
        }

    /**
     * 555 vodshow 精细筛选列表（地区/排序/类型/语言/年份 + 分页）。
     */
    suspend fun category555(
        cat: Int, area: String, by: String, cls: String,
        lang: String, year: String, page: Int,
    ): PageData = Parser55.parsePage(
        getHtml(Parser55.vodshowUrl(cat, area, by, cls, lang, year, page)), page
    )

    private val filtersCache = java.util.concurrent.ConcurrentHashMap<String, List<Parser55.FilterGroup>>()

    /** 555 筛选选项组（按 分类+域名 缓存，站点改动或切域名后自动失效重取） */
    suspend fun filters555(cat: Int): List<Parser55.FilterGroup> {
        val key = "${Src.DY555.base}|$cat"
        filtersCache[key]?.let { return it }
        val groups = Parser55.parseFilters(getHtml(Parser55.vodshowUrl(cat)))
        if (groups.isNotEmpty()) filtersCache[key] = groups
        return groups
    }

    /** 搜索（按来源） */
    suspend fun search(src: Src, kw: String, page: Int): PageData {
        val encoded = java.net.URLEncoder.encode(kw, "UTF-8")
        return when (src) {
            Src.JUZI -> {
                val html = getHtml(Parser.searchUrl(encoded, page))
                if (Parser.isSearchEmpty(html)) PageData(emptyList(), page, 1)
                else Parser.parsePage(html, page)
            }
            Src.DY555 -> {
                val html = getHtml(Parser55.searchUrl(encoded))
                if (Parser55.isSearchEmpty(html)) PageData(emptyList(), 1, 1)
                else Parser55.parsePage(html, 1)
            }
        }
    }

    /** 详情（含全部线路×分集，按来源） */
    suspend fun detail(src: Src, id: Int): VodDetail = when (src) {
        Src.JUZI -> Parser.parseDetail(getHtml(Parser.detailUrl(id)), id)
        Src.DY555 -> Parser55.parseDetail(getHtml(Parser55.detailUrl(id)), id)
    }

    /** 播放页 → 播放地址（player_aaaa 两站同构） */
    suspend fun stream(src: Src, id: Int, sid: Int, nid: Int): StreamInfo {
        val html = when (src) {
            Src.JUZI -> getHtml(Parser.playUrl(id, sid, nid))
            Src.DY555 -> getHtml(Parser55.playUrl(id, sid, nid))
        }
        val info = Parser.parsePlayPage(html)
            ?: throw IllegalStateException("未能从播放页提取视频地址 (src=${src.key} id=$id sid=$sid nid=$nid)")
        return info.copy(nid = nid)
    }

    // ---------- 播放前广告过滤 ----------

    /**
     * 取流预处理：拉取 m3u8 → 剔除 CDN 注入的广告分片（博彩等）→ 落盘为本地播放列表。
     *
     * @return 可直接交给播放器的地址。无广告时返回原始 url；
     *         有广告时返回 "file://..." 本地播放列表（分片已改写为绝对地址）。
     *         任何异常都不会阻断播放：失败时静默返回原始 url。
     */
    suspend fun prepareStream(url: String, cacheDir: java.io.File): PreparedStream = withContext(Dispatchers.IO) {
        try {
            var text = fetchText(url)
            var effectiveUrl = url

            // master 播放列表 → 跟进第一个档位得到媒体列表
            if (text.lines().any { it.startsWith("#EXT-X-STREAM-INF") }) {
                val base = java.net.URI(url)
                val variant = text.lines().firstOrNull {
                    it.isNotBlank() && !it.startsWith("#")
                } ?: throw IllegalStateException("master m3u8 无可用档位")
                effectiveUrl = M3u8Filter.resolveUri(base, variant) ?: url
                text = fetchText(effectiveUrl)
            }

            val result = M3u8Filter.clean(text, effectiveUrl) ?: return@withContext PreparedStream(url, 0)
            // 过滤后的播放列表落盘，分片已是绝对地址，本地文件可直接播放
            val dir = java.io.File(cacheDir, "hls").apply { mkdirs() }
            val f = java.io.File(dir, java.lang.Integer.toHexString(url.hashCode()) + ".m3u8")
            f.writeText(result.cleaned)
            PreparedStream("file://" + f.absolutePath, result.removedSegments)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            // 过滤失败绝不阻断播放
            PreparedStream(url, 0)
        }
    }

    private fun fetchText(url: String): String {
        val builder = Request.Builder().url(url)
        BrowserHeaders.media().forEach { (name, value) -> builder.header(name, value) }
        return http.newCall(builder.build()).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}: $url" }
            resp.body?.string() ?: throw IllegalStateException("空响应: $url")
        }
    }
}

/** 取流预处理结果 */
data class PreparedStream(val url: String, val removedAdSegments: Int)
