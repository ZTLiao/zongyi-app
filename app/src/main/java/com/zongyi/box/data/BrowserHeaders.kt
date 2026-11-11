package com.zongyi.box.data

/**
 * 统一的"浏览器伪装"请求头。
 *
 * 站点/CDN 可能按请求头区分浏览器与爬虫流量，三处请求入口全部接此模块：
 * 1. HTML 页面请求（SiteApi.getHtml）→ [document]
 * 2. 视频流 m3u8/分片（PlayerActivity 的 DefaultHttpDataSource）→ [media]
 * 3. 封面图片（App 的 Coil ImageLoader）→ [media]
 *
 * 注意：不要手动设置 Accept-Encoding，OkHttp 会自动加 gzip 并负责解压。
 */
object BrowserHeaders {

    /** Chrome 120 移动版 UA，与 Pixel 7 真机一致 */
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * 页面导航（HTML 文档）请求头，模拟 Chrome 手机浏览器直接打开页面。
     * @param referer 来源页，默认站点首页
     */
    fun document(referer: String = Parser.BASE + "/"): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to
            "text/html,application/xhtml+xml,application/xml;q=0.9," +
            "image/avif,image/webp,image/apng,*/*;q=0.8," +
            "application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1",
        "Referer" to referer,
    )

    /**
     * 媒体/资源请求头（m3u8、ts 分片、封面图），模拟页面内资源加载。
     * @param referer 来源页，默认站点首页
     */
    fun media(referer: String = Parser.BASE + "/"): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site",
        "Referer" to referer,
    )
}
