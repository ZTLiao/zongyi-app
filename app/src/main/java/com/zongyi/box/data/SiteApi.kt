package com.zongyi.box.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 站点请求层。只发 GET、只读 HTML，不执行任何站点脚本（天然无广告/无第三方跳转）。
 */
object SiteApi {

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        // 兜底整个调用（含 DNS 解析阶段）：DNS 挂起不受 connect/read 超时约束，
        // 曾出现搜索永久 loading 的卡死，callTimeout 保证请求最终一定结束
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private suspend fun getHtml(url: String): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
        BrowserHeaders.document().forEach { (name, value) -> builder.header(name, value) }
        val req = builder.build()
        try {
            val html = http.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "HTTP ${resp.code}: $url" }
                resp.body?.string() ?: throw IllegalStateException("空响应: $url")
            }
            // 成功即写缓存，供后续失败时兜底
            HttpCache.save(url, html)
            html
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            // 网络失败/超时：回读历史缓存，保证界面有数据、App 不崩
            val cached = HttpCache.load(url)
            if (cached != null) cached else throw e
        }
    }

    /** 首页 */
    suspend fun home(): HomeData = Parser.parseHome(getHtml(Parser.BASE + "/"))

    /** 分类列表 */
    suspend fun category(cat: Int, sort: String, year: String, page: Int): PageData =
        Parser.parsePage(getHtml(Parser.categoryUrl(cat, sort, "", page, year)), page)

    /** 搜索 */
    suspend fun search(kw: String, page: Int): PageData {
        val html = getHtml(Parser.searchUrl(java.net.URLEncoder.encode(kw, "UTF-8"), page))
        if (Parser.isSearchEmpty(html)) return PageData(emptyList(), page, 1)
        return Parser.parsePage(html, page)
    }

    /** 详情（含全部线路×分集） */
    suspend fun detail(id: Int): VodDetail =
        Parser.parseDetail(getHtml(Parser.detailUrl(id)), id)

    /** 播放页 → 播放地址 */
    suspend fun stream(id: Int, sid: Int, nid: Int): StreamInfo {
        val info = Parser.parsePlayPage(getHtml(Parser.playUrl(id, sid, nid)))
            ?: throw IllegalStateException("未能从播放页提取视频地址 (id=$id sid=$sid nid=$nid)")
        return info.copy(nid = nid)
    }
}
