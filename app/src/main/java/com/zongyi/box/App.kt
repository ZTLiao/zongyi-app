package com.zongyi.box

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.zongyi.box.data.BrowserHeaders
import com.zongyi.box.data.DomainStore
import com.zongyi.box.data.HttpCache
import com.zongyi.box.data.Parser
import okhttp3.OkHttpClient

class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        HttpCache.init(this)
        DomainStore.init(this)
    }

    /**
     * 封面图等图片请求统一走 Coil 全局 ImageLoader，
     * 拦截器为每个请求注入浏览器头（UA/Referer/Accept-Language），
     * 与 SiteApi、播放器的伪装策略保持一致。
     * 555影视等站点的图片要求 no-referrer（页面 referrerpolicy="no-referrer"），
     * 非综艺盒子域名的图片不带 Referer，避免防盗链拦截。
     */
    override fun newImageLoader(): ImageLoader {
        val client = OkHttpClient.Builder()
            // 封面图域名同样走 DoH 兜底解析（防 DNS 污染导致图片全挂）
            .dns(com.zongyi.box.data.SmartDns)
            .addInterceptor { chain ->
                val req = chain.request()
                val builder = req.newBuilder()
                val headers = if (req.url.host.contains("ijujizy")) BrowserHeaders.media()
                else BrowserHeaders.mediaNoRef
                headers.forEach { (name, value) -> builder.header(name, value) }
                chain.proceed(builder.build())
            }
            .build()
        return ImageLoader.Builder(this)
            .okHttpClient(client)
            // 站点 CDN 常下发 no-store/no-cache，会导致封面反复重新下载、滑动卡顿；
            // 这里无视响应缓存头，封面一律走 Coil 本地磁盘缓存（海报内容基本不变）
            .respectCacheHeaders(false)
            .build()
    }
}
