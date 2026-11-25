package com.zongyi.box.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 88看球数据接口。
 *
 * - 页面走 https + 候选域名故障切换（与影视源一致的策略）
 * - 内置域名全部不可用时，自动访问官方地址发布页 [LiveSite.indexUrls] 发现新镜像
 * - 线路接口 /live/{id}/source 返回混淆 Base64 JSON，解析为 m3u8 直链；
 *   当前站线路为空（未开播/线路被删）时逐镜像重取
 * - 内存级 TTL 缓存：赛程 2 分钟、录像 10 分钟、线路 40 秒（auth_key 有时效）
 * - 当前可用域名与发现的镜像持久化，下次启动直接使用
 */
object LiveApi {

    private const val TAG = "ZongYiBox"

    private const val PREFS = "live_site"
    private const val KEY_BASE = "base"
    private const val KEY_MIRRORS = "mirrors"

    /** 发布页刷新冷却：避免每次请求失败都去拉发布页 */
    private const val MIRROR_COOLDOWN_MS = 60_000L

    /**
     * 非 API 页面必须命中的内容签名。
     * 不同镜像下发的模板标题不同（.la / .love 标题不含「88看球」），
     * 因此同时接受 CMS 的结构特征，避免误判为"APP 下载引导页"。
     */
    private val pageMarkers = listOf(
        "88看球", "88直播", "group-game-item", "live-list-group-time", "playback-detail-title",
    )

    private data class Entry(val text: String, val at: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    /** 发布页发现的镜像域名（最新发现的排前） */
    private val mirrors = mutableListOf<String>()

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var lastMirrorAt = 0L

    /**
     * 域名自动切换回调（UI 提示用）。
     * 可能在 IO 线程触发，实现方需自行切回主线程。
     */
    @Volatile
    var onSiteSwitch: ((String) -> Unit)? = null

    fun init(context: Context) {
        val p = runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
            .getOrNull() ?: return
        prefs = p
        p.getString(KEY_BASE, null)?.takeIf { it.startsWith("http") }?.let { LiveSite.base = it }
        p.getString(KEY_MIRRORS, null)?.split('\n')
            ?.filter { it.startsWith("http") }
            ?.let {
                synchronized(mirrors) {
                    mirrors.clear()
                    mirrors.addAll(it)
                }
            }
    }

    suspend fun home(ttlMs: Long = 2 * 60_000L): LiveHome = withContext(Dispatchers.IO) {
        ParserLive.parseHome(get("/", ttlMs))
    }

    suspend fun replays(page: Int, ttlMs: Long = 10 * 60_000L): ReplayPage {
        val path = if (page <= 1) "/replay" else "/replay?page=$page"
        return withContext(Dispatchers.IO) {
            ParserLive.parseReplayPage(get(path, ttlMs), page)
        }
    }

    suspend fun replayDetail(id: Int, ttlMs: Long = 10 * 60_000L): ReplayDetail =
        withContext(Dispatchers.IO) {
            ParserLive.parseReplayDetail(get("/replay/$id", ttlMs), id)
        }

    /** 某场比赛的全部可播放信号（已剥离包装页） */
    suspend fun sources(matchId: Int, ttlMs: Long = 40_000L): List<LiveLink> =
        withContext(Dispatchers.IO) {
            val path = "/live/$matchId/source"

            /**
             * 站点常一次性下发多条同名线路（旧 auth_key 已过期 + 新签名），
             * 旧的必 403；这里先滤掉过期项，并按过期时间倒序，让默认线路更可能可用。
             */
            fun prepare(raw: String): List<LiveLink> {
                val list = ParserLive.decodeSourceJson(raw)
                if (list.isEmpty()) return emptyList()
                val sorted = list.sortedByDescending { ParserLive.expireAt(it.url) }
                val fresh = sorted.filterNot { ParserLive.isExpired(it.url) }
                    .distinctBy { it.name }
                return fresh.ifEmpty { sorted.distinctBy { it.name } }
            }

            val primary = runCatching { prepare(get(path, ttlMs, isApi = true)) }
                .getOrDefault(emptyList())
            if (primary.any { !ParserLive.isExpired(it.url) }) return@withContext primary

            // 全部过期：绕过缓存重新请求一次，站点每次请求都会重新签名
            val resigned = runCatching {
                prepare(fetchOnce(LiveSite.base + path, LiveSite.base, isApi = true))
            }.getOrDefault(emptyList())
            if (resigned.any { !ParserLive.isExpired(it.url) }) return@withContext resigned

            // 当前站线路为空或全部过期（未开播、线路被删、站被墙）：刷新发布页后逐镜像重取
            refreshMirrors(force = true)
            for (base in candidates()) {
                try {
                    val list = prepare(fetchOnce(base + path, base, isApi = true))
                    if (list.isEmpty() || list.none { !ParserLive.isExpired(it.url) }) continue
                    switchTo(base)
                    return@withContext list
                } catch (e: Exception) {
                    Log.w(TAG, "直播线路请求失败: $base$path", e)
                }
            }
            // 兜底：返回已解析列表（可能全部过期），由播放页逐条尝试并自动刷新
            primary
        }

    /**
     * 播放前探测线路：并发请求每条线路的 m3u8，把能返回 2xx 的线路排到前面。
     *
     * 站点下发的地址短时有效（几分钟后可能 404/403），直接开播会先黑屏等失败，
     * 因此先探测再播；探测与播放使用同一套 media 请求头，结果具有参考性。
     *
     * @return 排序后的线路 + 探测可用的数量（0 表示全部不可用）
     */
    suspend fun rankPlayable(links: List<LiveLink>): PlayableRanking = withContext(Dispatchers.IO) {
        if (links.isEmpty()) return@withContext PlayableRanking(emptyList(), 0)
        val codes = coroutineScope {
            links.map { link -> async { probe(link) } }.awaitAll()
        }
        val working = links.filterIndexed { i, _ -> codes[i] in 200..299 }
        val rest = links.filterIndexed { i, _ -> codes[i] !in 200..299 }
        // 全部返回明确的 HTTP 错误（非超时/网络异常）时，说明线路确实都不可用
        val allRejected = codes.all { it != 0 && it !in 200..299 }
        PlayableRanking(working + rest, working.size, allRejected)
    }

    /** 探测结果；working 为可用线路数量 */
    data class PlayableRanking(
        val links: List<LiveLink>,
        val working: Int,
        /** 所有线路都返回了明确的 HTTP 错误码（不会因为探测超时而误判） */
        val allRejected: Boolean = false,
    ) {
        /** 探测可用的线路 */
        val playable: List<LiveLink>
            get() = links.take(working)
    }

    private val probeClient: OkHttpClient by lazy {
        SiteApi.http.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** 探测时最多读取的播放列表字节数（录像 VOD 列表可能很大） */
    private const val PROBE_BODY_LIMIT = 256L * 1024

    /**
     * 探测单条线路，返回最终可用的 HTTP 状态码；网络异常/超时/内容不是 m3u8 均返回 0。
     *
     * 只检查播放列表不够：比赛结束后部分 CDN 仍保留 m3u8（200 且内容合法），
     * 但分片已被删除，播放器取分片时才 404。因此这里要一路校验到分片。
     */
    private fun probe(link: LiveLink): Int {
        val headers = if (link.referer.contains("88player.top", ignoreCase = true)) {
            BrowserHeaders.mediaNoRef
        } else {
            BrowserHeaders.media(link.referer.ifBlank { LiveSite.base + "/" })
        }
        val playlist = link.url.toHttpUrlOrNull()
        val code = if (playlist == null) 0 else runCatching { probeStream(playlist, headers, 0) }.getOrDefault(0)
        Log.i(TAG, "线路探测 ${link.name} = $code")
        return code
    }

    private fun probeStream(url: HttpUrl, headers: Map<String, String>, depth: Int): Int {
        val resp = probeClient.newCall(getRequest(url, headers)).execute().use { r ->
            if (!r.isSuccessful) return r.code
            // 录像的 VOD 播放列表可达数 MB，读完会超时；只取前 256KB 足够判断
            ProbeResponse(r.peekBody(PROBE_BODY_LIMIT).string(), r.request.url)
        }
        val body = resp.body.trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        // 站点 CDN 会用 HTTP 200 返回 HTML 错误页，必须确认内容是 m3u8
        if (!body.startsWith("#EXTM3U")) return 0
        val lines = body.lineSequence().toList()
        // 截断读取时最后一行可能不完整，丢弃
        val usable = if (body.endsWith("\n")) lines else lines.dropLast(1)
        val uris = usable
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        if (uris.isEmpty()) return 0
        if (body.contains("#EXT-X-STREAM-INF")) {
            if (depth >= 1) return 0
            val variant = resp.url.resolve(uris.first()) ?: return 0
            return probeStream(variant, headers, depth + 1)
        }
        // 直播播放列表取最后一个分片（最靠前的分片可能已过期）
        val segment = resp.url.resolve(uris.last()) ?: return 0
        return runCatching {
            probeClient.newCall(getRequest(segment, headers)).execute().use { it.code }
        }.getOrDefault(0)
    }

    private fun getRequest(url: HttpUrl, headers: Map<String, String>): Request =
        Request.Builder()
            .url(url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .get()
            .build()

    private class ProbeResponse(val body: String, val url: HttpUrl)

    private fun get(path: String, ttlMs: Long, isApi: Boolean = false): String {
        val now = System.currentTimeMillis()
        if (ttlMs > 0) {
            cache[path]?.let {
                if (now - it.at <= ttlMs) return it.text
            }
        }

        var text = tryFetch(path, isApi)
        if (text == null && refreshMirrors(force = true)) {
            // 内置域名全部不可用：从发布页拿到新镜像后重试一次
            text = tryFetch(path, isApi)
        }
        if (text != null) {
            if (cache.size > 120) cache.clear()
            cache[path] = Entry(text, now)
            return text
        }
        // 全部域名失败：回读未过期缓存兜底
        cache[path]?.let { return it.text }
        throw IllegalStateException("直播源加载失败: $path")
    }

    /** 依次尝试「当前域名 + 发布页镜像 + 内置候选」，成功即固化当前域名 */
    private fun tryFetch(path: String, isApi: Boolean): String? {
        for (base in candidates()) {
            try {
                val text = fetchOnce(base + path, base, isApi)
                if (!isApi && pageMarkers.none { text.contains(it) }) {
                    throw IllegalStateException("响应非 88 看球内容页: $base")
                }
                switchTo(base)
                return text
            } catch (e: Exception) {
                Log.w(TAG, "直播页面请求失败: $base$path", e)
            }
        }
        return null
    }

    /**
     * 从官方地址发布页发现最新可用镜像。
     * @param force true 时忽略冷却（整站全部失败、线路为空等需要立即发现新站的场景）
     * @return 是否拿到镜像列表
     */
    private fun refreshMirrors(force: Boolean): Boolean {
        val now = System.currentTimeMillis()
        if (!force && now - lastMirrorAt < MIRROR_COOLDOWN_MS) {
            return synchronized(mirrors) { mirrors.isNotEmpty() }
        }
        lastMirrorAt = now

        for (index in LiveSite.indexUrls) {
            try {
                val found = ParserLive.parseMirrorBases(fetchRaw(index))
                if (found.isEmpty()) continue
                val fresh = found.filter { it !in LiveSite.bases }
                synchronized(mirrors) {
                    val added = fresh.filter { it !in mirrors }
                    if (added.isNotEmpty()) {
                        mirrors.addAll(0, added)
                        persist()
                        Log.i(TAG, "发布页发现镜像: ${added.joinToString()}")
                    }
                }
                return true
            } catch (e: Exception) {
                Log.w(TAG, "地址发布页请求失败: $index", e)
            }
        }
        return false
    }

    /** 候选域名顺序：当前生效域名 → 发布页发现的镜像 → 内置域名 */
    private fun candidates(): List<String> {
        val out = LinkedHashSet<String>()
        out.add(LiveSite.base)
        synchronized(mirrors) { out.addAll(mirrors) }
        out.addAll(LiveSite.bases)
        return out.toList()
    }

    private fun switchTo(base: String) {
        if (LiveSite.base == base) return
        LiveSite.base = base
        persist()
        Log.i(TAG, "直播源已切换到域名: $base")
        onSiteSwitch?.invoke(base)
    }

    private fun persist() {
        val p = prefs ?: return
        val mirrorText = synchronized(mirrors) { mirrors.joinToString("\n") }
        runCatching {
            p.edit()
                .putString(KEY_BASE, LiveSite.base)
                .putString(KEY_MIRRORS, mirrorText)
                .apply()
        }
    }

    /** 地址发布页本身的请求（不属于任何站源，不做内容校验） */
    private fun fetchRaw(url: String): String {
        val builder = Request.Builder().url(url)
        BrowserHeaders.document(url).forEach { (name, value) -> builder.header(name, value) }
        return SiteApi.http.newCall(builder.build()).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}: $url" }
            resp.body?.string() ?: throw IllegalStateException("空响应: $url")
        }
    }

    private fun fetchOnce(url: String, base: String, isApi: Boolean): String {
        val builder = Request.Builder().url(url)
        BrowserHeaders.document(base + "/").forEach { (name, value) -> builder.header(name, value) }
        if (isApi) {
            builder.header("Accept", "application/json, text/javascript, */*; q=0.01")
            builder.header("X-Requested-With", "XMLHttpRequest")
        }
        return SiteApi.http.newCall(builder.build()).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}: $url" }
            resp.body?.string() ?: throw IllegalStateException("空响应: $url")
        }
    }
}
