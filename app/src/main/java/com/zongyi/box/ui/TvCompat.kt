package com.zongyi.box.ui

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * TV / 智能电视 / 盒子适配工具。
 *
 * 判定依据（任一命中即视为电视类设备，避免国产盒子不上报 TV UI Mode 的兼容性问题）：
 * 1. 声明了 LEANBACK 系统特性（标准 Android TV）
 * 2. UI Mode 为 TELEVISION
 * 3. 没有触屏特性（电视/盒子普遍无触摸屏，手机/平板必有）
 */
object TvCompat {

    fun isTv(ctx: Context): Boolean {
        val pm = ctx.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
        val mode = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (mode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        return !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    /**
     * TV/盒子上横屏；手机保持布局声明的方向。
     * 部分 TV ROM 对 setRequestedOrientation 抛 IllegalStateException/IllegalArgumentException，
     * 横屏只是体验优化，失败直接忽略，绝不因它崩溃。
     */
    fun applyOrientation(activity: Activity) {
        try {
            if (isTv(activity)) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        } catch (_: Throwable) {
        }
    }
}

