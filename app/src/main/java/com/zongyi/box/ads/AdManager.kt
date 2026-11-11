package com.zongyi.box.ads

import android.app.Activity
import android.app.Application
import android.os.SystemClock
import co.notix.interstitial.InterstitialLoader
import co.notix.interstitial.NotixInterstitial

/**
 * Monetag 广告管理器（Notix 内核）。
 *
 * 使用步骤：
 * 1. 在 https://publishers.monetag.com 注册账号；
 * 2. 后台「Create new ad」→「Add new Android app」（分发渠道选 SDK）；
 * 3. 勾选 Interstitial 格式后，平台生成 Zone ID，填入 [INTERSTITIAL_ZONE_ID]；
 * 4. 收款：后台 Payments → Cryptocurrencies → USDT（TRC20/ERC20/BEP20），最低提现 $100。
 *
 * ZONE_ID <= 0 时为未配置状态：SDK 不初始化、不加载、不展示，App 正常运行。
 */
object AdManager {

    /** Monetag 插屏广告 Zone ID（后台创建 Android App 后获得），0 为占位未配置 */
    private const val INTERSTITIAL_ZONE_ID = 0L

    /** 两次插屏广告之间的最小间隔（毫秒），避免打扰用户 */
    private const val MIN_INTERVAL_MS = 90_000L

    private var loader: InterstitialLoader? = null
    private var lastShowAt = 0L

    fun init(app: Application) {
        if (INTERSTITIAL_ZONE_ID <= 0) return
        runCatching {
            loader = NotixInterstitial.createLoader(INTERSTITIAL_ZONE_ID).also {
                it.startLoading()
            }
        }
    }

    /**
     * 展示插屏广告（可跳过）。内部自带频控：未配置 / SDK 未初始化 / 冷却期内
     * / 无填充时静默跳过，不影响 App 流程。
     */
    fun showInterstitial(activity: Activity) {
        val l = loader ?: return
        if (INTERSTITIAL_ZONE_ID <= 0) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastShowAt < MIN_INTERVAL_MS) return
        runCatching {
            l.doOnNextAvailable { result ->
                if (result != null) {
                    lastShowAt = SystemClock.elapsedRealtime()
                    runCatching { NotixInterstitial.show(result) }
                }
            }
        }
    }
}
