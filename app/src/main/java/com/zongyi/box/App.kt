package com.zongyi.box

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.zongyi.box.data.BrowserHeaders
import com.zongyi.box.data.HttpCache
import com.zongyi.box.data.Parser
import okhttp3.OkHttpClient

class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        HttpCache.init(this)
    }

    /**
     * 封面图等图片请求统一走 Coil 全局 ImageLoader，
     * 拦截器为每个请求注入浏览器头（UA/Referer/Accept-Language），
     * 与 SiteApi、播放器的伪装策略保持一致。
     */
    override fun newImageLoader(): ImageLoader {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                BrowserHeaders.media().forEach { (name, value) -> builder.header(name, value) }
                chain.proceed(builder.build())
            }
            .build()
        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .build()
    }
}
