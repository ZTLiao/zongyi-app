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

    /** 详情页新鲜缓存窗口：进入详情页/播放器先显缓存秒开，后台再刷新 */
    const val DETAIL_TTL_MS = 15 * 60_000L

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        // 兜底整个调用（含 DNS 解析阶段）：DNS 挂起不受 connect/read 超时约束，
        // 曾出现搜索永久 loading 的卡死，callTimeout 保证请求最终一定结束
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // 站点主域优先 DoH、系统失败 DoH 兜底（防运营商 DNS 污染导致整源打不开）
        .dns(SmartDns)
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
                if (src != null) SourceHealth.markSuccess(src)
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
        // 有缓存兜底但站点已连不上：仍计一次失败，连续失败将触发整源隐藏
        if (src != null) SourceHealth.markFailure(src)
        return@withContext cached ?: throw (lastError ?: IllegalStateException("加载失败: $url"))
    }

    private fun fetchHtmlOnce(url: String): String {
        val builder = Request.Builder().url(url)
        BrowserHeaders.document().forEach { (name, value) -> builder.header(name, value) }
        return http.newCall(builder.build()).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}: $url" }
            resp.body?.string() ?: throw IllegalStateException("空响应: $url")
        }
    }

    /** 源健康探测用：拉取页面原文（不做域名切换、不计失败） */
    suspend fun fetchHtmlPublic(url: String): String = fetchHtmlOnce(url)

    /** 探测源当前是否可用（首页可访问且内容签名匹配） */
    suspend fun probe(src: Src): Boolean = try {
        val html = fetchHtmlPublic(src.base + "/")
        src.markers.any { html.contains(it) }
    } catch (_: Exception) {
        false
    }

    /**
     * 首页（按来源）。
     * @param ttlMs 新鲜缓存窗口（毫秒），>0 且缓存命中时直接解析缓存跳过网络（页面秒开）；
     *              拉刷新等强制更新场景传 0
     */
    suspend fun home(src: Src, ttlMs: Long = 0): HomeData = withContext(Dispatchers.Default) {
        val t0 = System.currentTimeMillis()
        val url = when (src) {
            Src.JUZI -> Parser.BASE + "/"
            Src.DY555 -> Src.DY555.base + "/"
            Src.TBL -> Src.TBL.base + "/"
            Src.MF -> Src.MF.base + "/"
        }
        HttpCache.loadFresh(url, ttlMs)?.let { cached ->
            val data = when (src) {
                Src.JUZI -> Parser.parseHome(cached)
                Src.DY555 -> Parser55.parseHome(cached)
                Src.TBL -> ParserTouhaos.parseHome(cached)
                Src.MF -> ParserMonkey.parseHome(cached)
            }
            Log.i(TAG, "首页[${src.label}] 缓存秒开 ${System.currentTimeMillis() - t0}ms")
            return@withContext data
        }
        val data = when (src) {
            Src.JUZI -> Parser.parseHome(getHtml(Parser.BASE + "/"))
            Src.DY555 -> Parser55.parseHome(getHtml(Src.DY555.base + "/"))
            Src.TBL -> ParserTouhaos.parseHome(getHtml(Src.TBL.base + "/"))
            Src.MF -> ParserMonkey.parseHome(getHtml(Src.MF.base + "/"))
        }
        Log.i(TAG, "首页[${src.label}] 网络加载 ${System.currentTimeMillis() - t0}ms")
        data
    }

    /** 分类列表（按来源）。ttlMs 同 home() */
    suspend fun category(
        src: Src, cat: Int, sort: String, year: String, page: Int, ttlMs: Long = 0,
    ): PageData {
        val url = when (src) {
            Src.JUZI -> Parser.categoryUrl(cat, sort, "", page, year)
            Src.DY555 -> Parser55.typeUrl(cat)
            Src.TBL -> ParserTouhaos.categoryUrl(cat, page)
            Src.MF -> ParserMonkey.browseUrl(cat, page)
        }
        return withContext(Dispatchers.Default) {
            val cached = HttpCache.loadFresh(url, ttlMs)
            if (cached != null) return@withContext when (src) {
                Src.JUZI -> Parser.parsePage(cached, page)
                Src.DY555 -> Parser55.parsePage(cached, page)
                Src.TBL -> ParserTouhaos.parsePage(cached, page)
                Src.MF -> ParserMonkey.parsePage(cached, page)
            }
            when (src) {
                Src.JUZI -> Parser.parsePage(getHtml(url), page)
                Src.DY555 -> Parser55.parsePage(getHtml(url), page)
                Src.TBL -> ParserTouhaos.parsePage(getHtml(url), page)
                Src.MF -> ParserMonkey.parsePage(getHtml(url), page)
            }
        }
    }

    /**
     * 555 vodshow 精细筛选列表（地区/排序/类型/语言/年份 + 分页）。ttlMs 同 home()
     */
    suspend fun category555(
        cat: Int, area: String, by: String, cls: String,
        lang: String, year: String, page: Int, ttlMs: Long = 0,
    ): PageData {
        val url = Parser55.vodshowUrl(cat, area, by, cls, lang, year, page)
        return withContext(Dispatchers.Default) {
            val cached = HttpCache.loadFresh(url, ttlMs)
            if (cached != null) return@withContext Parser55.parsePage(cached, page)
            Parser55.parsePage(getHtml(url), page)
        }
    }

    private val filtersCache = java.util.concurrent.ConcurrentHashMap<String, List<Parser55.FilterGroup>>()

    /** 555 筛选选项组（按 分类+域名 缓存，站点改动或切域名后自动失效重取） */
    suspend fun filters555(cat: Int): List<Parser55.FilterGroup> = withContext(Dispatchers.Default) {
        val key = "${Src.DY555.base}|$cat"
        filtersCache[key]?.let { return@withContext it }
        val groups = Parser55.parseFilters(getHtml(Parser55.vodshowUrl(cat)))
        if (groups.isNotEmpty()) filtersCache[key] = groups
        groups
    }

    /** 搜索（按来源） */
    suspend fun search(src: Src, kw: String, page: Int): PageData {
        val encoded = java.net.URLEncoder.encode(kw, "UTF-8")
        return withContext(Dispatchers.Default) {
            when (src) {
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
                // 蓝光影院搜索被站点安全验证拦截，直接返回空（不发起无效请求）
                Src.TBL -> PageData(emptyList(), page, 1)
                Src.MF -> {
                    val html = getHtml(ParserMonkey.searchUrl(encoded))
                    if (ParserMonkey.isSearchEmpty(html)) PageData(emptyList(), 1, 1)
                    else ParserMonkey.parsePage(html, 1)
                }
            }
        }
    }

    /** 详情（含全部线路×分集，按来源）。ttlMs 同 home() */
    suspend fun detail(src: Src, id: Int, ttlMs: Long = 0): VodDetail {
        val t0 = System.currentTimeMillis()
        val url = when (src) {
            Src.JUZI -> Parser.detailUrl(id)
            Src.DY555 -> Parser55.detailUrl(id)
            Src.TBL -> ParserTouhaos.detailUrl(id)
            Src.MF -> ParserMonkey.detailUrl(id)
        }
        return withContext(Dispatchers.Default) {
            val cached = HttpCache.loadFresh(url, ttlMs)
            if (cached != null) {
                val d = when (src) {
                    Src.JUZI -> Parser.parseDetail(cached, id)
                    Src.DY555 -> Parser55.parseDetail(cached, id)
                    Src.TBL -> ParserTouhaos.parseDetail(cached, id)
                    Src.MF -> ParserMonkey.parseDetail(cached, id)
                }
                Log.i(TAG, "详情[$id] 缓存秒开 ${System.currentTimeMillis() - t0}ms")
                return@withContext d
            }
            val d = when (src) {
                Src.JUZI -> Parser.parseDetail(getHtml(url), id)
                Src.DY555 -> Parser55.parseDetail(getHtml(url), id)
                Src.TBL -> ParserTouhaos.parseDetail(getHtml(url), id)
                Src.MF -> ParserMonkey.parseDetail(getHtml(url), id)
            }
            Log.i(TAG, "详情[$id] 网络加载 ${System.currentTimeMillis() - t0}ms")
            d
        }
    }

    // ---------- 播放链路会话缓存 ----------

    /** 播放地址记忆（切集往返秒开，会话级，超量整体清空） */
    private val streamCache = java.util.concurrent.ConcurrentHashMap<String, StreamInfo>()
    /** 已清洗 m3u8 的本地播放列表：url → (文件, 过滤分片数)，避免重复拉取/清洗 */
    private val cleanedPlaylists = java.util.concurrent.ConcurrentHashMap<String, kotlin.Pair<String, Int>>()

    /** 播放页 → 播放地址（player_aaaa 两站同构；TBL/MF 走各自链路） */
    suspend fun stream(src: Src, id: Int, sid: Int, nid: Int): StreamInfo {
        val ck = "${src.key}|$id|$sid|$nid"
        streamCache[ck]?.let { return it }
        val info: StreamInfo = withContext(Dispatchers.Default) {
            when (src) {
                Src.JUZI -> {
                    val html = getHtml(Parser.playUrl(id, sid, nid))
                    Parser.parsePlayPage(html)?.let {
                        it.copy(nid = nid)
                    } ?: throw IllegalStateException(
                        "未能从播放页提取视频地址 (src=${src.key} id=$id sid=$sid nid=$nid)")
                }
                Src.DY555 -> {
                    val html = getHtml(Parser55.playUrl(id, sid, nid))
                    Parser.parsePlayPage(html)?.let {
                        it.copy(nid = nid)
                    } ?: throw IllegalStateException(
                        "未能从播放页提取视频地址 (src=${src.key} id=$id sid=$sid nid=$nid)")
                }
                Src.TBL -> {
                    // 播放页 var now="https://host/share/<hash>" → share 页 const url= → m3u8 直链
                    val playHtml = getHtml(ParserTouhaos.playUrl(id, sid, nid))
                    val shareUrl = ParserTouhaos.parsePlayShareUrl(playHtml)
                        ?: throw IllegalStateException("播放页缺少 share 地址 (src=tbl id=$id sid=$sid nid=$nid)")
                    val shareHtml = getHtml(shareUrl)
                    val m3u8 = ParserTouhaos.parseShareM3u8(shareUrl, shareHtml)
                        ?: throw IllegalStateException("share 页未找到 m3u8 地址 (id=$id sid=$sid nid=$nid)")
                    StreamInfo(m3u8, "蓝光影院", nid, prevNid = null, nextNid = null)
                }
                Src.MF -> {
                    // 播放地址直接存在详情数据里，复用详情缓存（15 分钟窗口内秒取）
                    val d = detail(src, id, DETAIL_TTL_MS)
                    val line = d.source(sid)
                        ?: throw IllegalStateException("猴影工坊无该线路 (id=$id sid=$sid)")
                    val idx = line.episodes.indexOfFirst { it.nid == nid }
                    val ep = line.episodes.getOrNull(idx)
                        ?: throw IllegalStateException("猴影工坊无该分集 (id=$id sid=$sid nid=$nid)")
                    StreamInfo(
                        ep.url,
                        "猴影工坊", nid,
                        prevNid = line.episodes.getOrNull(idx - 1)?.nid,
                        nextNid = line.episodes.getOrNull(idx + 1)?.nid,
                    )
                }
            }
        }
        if (streamCache.size >= 40) streamCache.clear()
        streamCache[ck] = info
        return info
    }

    /**
     * 静默预取：播放地址（内存缓存）+ 清洗后的本地播放列表（磁盘复用）。
     * 在详情页/播放器后台调用，用户点击播放时全部命中缓存，点击即播。
     * 任何失败都静默吞掉（预取只是加速，不承担功能）。
     */
    suspend fun prefetchStream(src: Src, id: Int, sid: Int, nid: Int, cacheDir: java.io.File) {
        runSuspendCatching {
            val info = stream(src, id, sid, nid)
            prepareStream(info.url, cacheDir)
        }
    }

    /**
     * 取流预处理：拉取 m3u8 → 剔除 CDN 注入的广告分片（博彩等）→ 落盘为本地播放列表。
     * 同一 url 本会话内已清洗过 → 直接复用本地播放列表，零网络等待。
     *
     * @return 可直接交给播放器的地址。无广告时返回原始 url；
     *         有广告时返回 "file://..." 本地播放列表（分片已改写为绝对地址）。
     *         任何异常都不会阻断播放：失败时静默返回原始 url。
     */
    suspend fun prepareStream(url: String, cacheDir: java.io.File): PreparedStream = withContext(Dispatchers.IO) {
        cleanedPlaylists[url]?.let { (path, removed) ->
            val f = java.io.File(path)
            if (f.exists() && f.length() > 0) return@withContext PreparedStream("file://" + path, removed)
            cleanedPlaylists.remove(url)
        }
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
            cleanedPlaylists[url] = kotlin.Pair(f.absolutePath, result.removedSegments)
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
