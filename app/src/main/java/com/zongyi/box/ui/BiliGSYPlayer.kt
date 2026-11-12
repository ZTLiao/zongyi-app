package com.zongyi.box.ui

import android.content.Context
import android.util.AttributeSet
import com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer
import com.zongyi.box.R

/**
 * B 站风格控制栏的 GSY 播放器。
 *
 * 通过重写 [getLayoutId] 注入自定义控制布局（view_player_custom）：
 * 控制项放在 GSY 的 layout_top / layout_bottom 容器内，
 * 自动继承"点击切换显隐 + 播放中自动隐藏"行为（与 B 站一致），
 * 不再需要外部常驻顶栏。
 */
class BiliGSYPlayer : StandardGSYVideoPlayer {

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    override fun getLayoutId(): Int = R.layout.view_player_custom
}
