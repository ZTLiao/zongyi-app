package com.zongyi.box

import android.app.Application
import androidx.appfunctions.service.AppFunctionConfiguration
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.zongyi.box.appfunctions.ZongYiAppFunctions
import com.zongyi.box.data.BrowserHeaders
import com.zongyi.box.data.DomainStore
import com.zongyi.box.data.HttpCache
import com.zongyi.box.data.Parser
import com.zongyi.box.data.SourceHealth
import com.zongyi.box.data.runSuspendCatching
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class App : Application(), ImageLoaderFactory, AppFunctionConfiguration.Provider {

    override fun onCreate() {
        super.onCreate()
        HttpCache.init(this)
        DomainStore.init(this)
        SourceHealth.init(this)
        // appfunction AI 接入桥：用户开启过则随冷启动恢复监听
        if (com.zongyi.box.data.AiBridge.isEnabled(this)) {
            com.zongyi.box.data.AiBridge.start(this)
        }
        // 后台探测被隐藏的失效源，恢复可用则自动解除隐藏
        kotlinx.coroutines.MainScope().launch {
            runSuspendCatching { SourceHealth.recheckHidden() }
        }
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

    /**
     * Android MCP（AppFunctions）：按 Google 官方规范向系统注册 AI 可调用功能，
     * Android 16+ 上 Gemini 等系统 Agent 可发现并调用（见 appfunctions/ZongYiAppFunctions.kt）。
     */
    override val appFunctionConfiguration: AppFunctionConfiguration
        get() = AppFunctionConfiguration.Builder()
            .addEnclosingClassFactory(ZongYiAppFunctions::class.java) { ZongYiAppFunctions(this) }
            .build()
}
