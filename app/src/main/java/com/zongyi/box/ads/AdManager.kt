package com.zongyi.box.ads

import android.app.Activity
import android.app.Application
import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import com.zongyi.box.R

/**
 * 广告管理器 — Adsterra 方案（纯 WebView，无第三方 SDK）。
 *
 * 两个广告位共用同一个 Direct Link（SmartLink），未配置时静默跳过：
 *
 * 1. 开屏广告：App 启动时以全屏 WebView 打开 [SMARTLINK_URL]，5 秒后可关闭；
 * 2. 详情页 banner：60dp 自绘横幅，点击后以应用内 WebView 打开 [SMARTLINK_URL]。
 *
 * Adsterra 收款：后台 Payment info → USDT 加密提现，最低 $100，双周结算。
 * 若后续审核通过拿到正式 Banner 标签地址，填入 [BANNER_TAG_URL] 后
 * 详情页横幅会自动切换为 WebView 渲染标签（优先级高于自绘横幅）。
 */
object AdManager {

    /** Adsterra Direct Link（SmartLink），空为未配置 */
    private const val SMARTLINK_URL = "https://auctionr.org/4/470fb5265f896ddff78c7f87fd30d401"

    /** Adsterra Banner 标签脚本地址（网站审核通过后获得），空为未配置 */
    private const val BANNER_TAG_URL = ""

    /** 是否启用开屏广告 */
    private const val APP_OPEN_ENABLED = true

    private var appOpenShown = false

    /** Adsterra 无需初始化 SDK，保留空实现供 App.onCreate 调用 */
    fun init(app: Application) = Unit

    /** 开屏广告：仅在 App 本次运行首次进入主界面时以全屏 WebView 展示一次 */
    fun showAppOpen(activity: Activity) {
        if (appOpenShown || !APP_OPEN_ENABLED) return
        appOpenShown = true
        if (SMARTLINK_URL.isBlank()) return
        runCatching {
            activity.startActivity(
                AdWebViewActivity.intent(activity, SMARTLINK_URL, appOpen = true)
            )
        }
    }

    /**
     * 详情页 banner，按优先级渲染：
     * 1. 配置了 [BANNER_TAG_URL] → WebView 渲染 Adsterra 广告标签；
     * 2. 否则 → inflate 自绘横幅（view_ad_banner），点击以应用内 WebView 打开 Direct Link；
     * 3. [SMARTLINK_URL] 也为空 → 不创建任何视图，容器保持 GONE。
     */
    fun attachBanner(container: FrameLayout) {
        if (container.childCount > 0) return
        when {
            BANNER_TAG_URL.isNotBlank() -> attachTagBanner(container)
            SMARTLINK_URL.isNotBlank() -> attachSmartLinkBanner(container)
            else -> return
        }
    }

    /** Adsterra 标签方案：WebView 加载广告脚本 */
    @SuppressLint("SetJavaScriptEnabled")
    private fun attachTagBanner(container: FrameLayout) {
        runCatching {
            val webView = WebView(container.context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                setBackgroundColor(Color.TRANSPARENT)
            }
            val html = """
                <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                <style>body{margin:0;padding:0;background:transparent;overflow:hidden}</style></head>
                <body><script src="$BANNER_TAG_URL"></script></body></html>
            """.trimIndent()
            webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            container.addView(webView)
            container.visibility = View.VISIBLE
        }
    }

    /** 自绘横幅方案：点击后以应用内 WebView 全屏打开 Direct Link */
    private fun attachSmartLinkBanner(container: FrameLayout) {
        runCatching {
            val view = LayoutInflater.from(container.context)
                .inflate(R.layout.view_ad_banner, container, false)
            view.setOnClickListener {
                runCatching {
                    it.context.startActivity(
                        AdWebViewActivity.intent(it.context, SMARTLINK_URL, appOpen = false)
                    )
                }
            }
            container.addView(view)
            container.visibility = View.VISIBLE
        }
    }
}
