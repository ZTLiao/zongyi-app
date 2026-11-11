package com.zongyi.box.ads

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.zongyi.box.R

/**
 * 全屏广告 WebView 页面。
 *
 * 两种模式（[EXTRA_APP_OPEN] 区分）：
 * - 开屏模式：右上角"跳过 Ns"倒计时，5 秒后变为可点击的 ✕ 关闭；
 * - Banner 点击模式：右上角直接显示 ✕，可随时关闭。
 *
 * 页面内 http/https 链接均在应用内继续加载（保证 Adsterra 跳转链路计费），
 * 非 http(s) 链接（intent://、market:// 等）转交系统处理。
 */
class AdWebViewActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var btnClose: TextView
    private lateinit var adProgress: View
    private lateinit var adHint: TextView

    private var remainSeconds = CLOSE_DELAY_SECONDS
    private val handler = Handler(Looper.getMainLooper())

    private val countdownTick = object : Runnable {
        override fun run() {
            remainSeconds--
            if (remainSeconds <= 0) {
                btnClose.text = "✕"
                btnClose.isEnabled = true
            } else {
                btnClose.text = "跳过 ${remainSeconds}s"
                handler.postDelayed(this, 1_000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ad_web)

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isBlank()) {
            finish()
            return
        }
        val appOpen = intent.getBooleanExtra(EXTRA_APP_OPEN, false)

        web = findViewById(R.id.adWebView)
        btnClose = findViewById(R.id.btnClose)
        adProgress = findViewById(R.id.adProgress)
        adHint = findViewById(R.id.adHint)

        if (appOpen) {
            btnClose.text = "跳过 ${remainSeconds}s"
            btnClose.isEnabled = false
            handler.postDelayed(countdownTick, 1_000L)
        } else {
            btnClose.text = "✕"
            btnClose.isEnabled = true
        }
        btnClose.setOnClickListener { finish() }

        setupWebView()
        web.loadUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        web.setBackgroundColor(android.graphics.Color.BLACK)
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                android.util.Log.i(TAG, "page start: $url")
                adProgress.visibility = View.VISIBLE
                adHint.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView, url: String) {
                android.util.Log.i(TAG, "page finish: $url")
                adProgress.visibility = View.GONE
                if (url.startsWith("http")) adHint.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView,
                request: android.webkit.WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                // 仅主框架失败才提示，子资源失败忽略
                if (request.isForMainFrame) {
                    android.util.Log.w(
                        TAG,
                        "main frame error: url=${request.url} code=${error.errorCode} desc=${error.description}"
                    )
                    adProgress.visibility = View.GONE
                    adHint.visibility = View.VISIBLE
                    // 加载失败时立即允许关闭，避免用户对着黑屏干等倒计时
                    handler.removeCallbacksAndMessages(null)
                    btnClose.text = "✕"
                    btnClose.isEnabled = true
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val uri = request.url
                return if (uri.scheme == "http" || uri.scheme == "https") {
                    false // 应用内继续加载，保证 Direct Link 跳转链路完整计费
                } else {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }
                    true
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) {
            web.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::web.isInitialized) {
            web.apply {
                stopLoading()
                destroy()
            }
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ZongYiAd"
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_APP_OPEN = "extra_app_open"
        private const val CLOSE_DELAY_SECONDS = 5

        fun intent(context: Context, url: String, appOpen: Boolean): Intent =
            Intent(context, AdWebViewActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_APP_OPEN, appOpen)
    }
}
