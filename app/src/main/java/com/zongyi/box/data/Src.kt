package com.zongyi.box.data

/**
 * 内容来源站。App 聚合多个 MacCMS 站点：
 * - JUZI 综艺盒子（m.ijujizy.com，定制 vr 模板）
 * - DY555 555影视（www.5k35.top，mxone 标准模板）
 * - TBL 蓝光影院（touhaos.top，myui 模板）
 * - MF 猴影工坊（monkey-flix.com，Astro SSR + MacCMS 数据）
 *
 * 每个源携带候选域名列表：当前域名不可访问时自动切换到下一个
 * （见 SiteApi.getHtml），切换结果由 DomainStore 持久化，下次启动直接用可用域名。
 * markers 为内容签名：候选域名的响应必须包含其一才接受为可用域名，
 * 防止切到"APP 下载引导页"之类的假内容站。
 */
enum class Src(
    val key: String,
    val label: String,
    defaultBase: String,
    defaultBases: List<String>,
    /** 内容签名（任一包含即视为真内容页） */
    val markers: List<String>,
) {
    JUZI(
        "jz", "综艺盒子",
        "https://m.ijujizy.com",
        listOf("https://m.ijujizy.com", "https://www.ijujizy.com", "https://ijujizy.com"),
        listOf("vrdetail", "vrplay", "vrartdetail"),
    ),
    DY555(
        "555", "555影视",
        "https://www.5k35.top",
        listOf("https://www.5k35.top", "https://5k35.top"),
        listOf("voddetail", "vodplay", "module-"),
    ),
    TBL(
        "tbl", "蓝光影院",
        "https://touhaos.top",
        listOf("https://touhaos.top", "https://www.touhaos.top"),
        listOf("myui-vodlist", "myui-panel"),
    ),
    MF(
        "mf", "猴影工坊",
        "https://monkey-flix.com",
        listOf("https://monkey-flix.com", "https://www.monkey-flix.com"),
        listOf("/watch/", "astro-"),
    );

    /** 当前生效域名（可能被故障切换/持久化覆盖） */
    var base: String = defaultBase

    /** 候选域名列表，顺序即优先级（var 仅为测试注入） */
    var bases: List<String> = defaultBases

    companion object {
        fun of(key: String?): Src = entries.firstOrNull { it.key == key } ?: JUZI
    }
}
