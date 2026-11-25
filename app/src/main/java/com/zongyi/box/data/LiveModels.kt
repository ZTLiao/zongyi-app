package com.zongyi.box.data

/**
 * 体育直播站（88看球）模型。
 * 站点为服务端渲染模板，App 只读 HTML + 官方直出的线路 JSON，不执行站点脚本。
 */
object LiveSite {
    /** 当前生效域名（故障切换 / 发布页发现镜像后自动更新） */
    @Volatile
    var base: String = "https://www.88kanqiu.cx"

    /**
     * 内置候选域名（顺序即优先级）。
     * 实际候选 = 当前域名 + 发布页发现的新域名 + 此列表。
     */
    val bases: List<String> = listOf(
        "https://www.88kanqiu.cx",
        "https://www.88kanqiu.la",
        "https://www.88kanqiu.one",
        "https://www.88kanqiu.ooo",
        "https://www.88kanqiu.pw",
        "https://www.88kanqiu.in",
        "https://www.88kanqiu.cv",
        "https://www.88kanqiu.bar",
        "https://www.88kanqiu.love",
        "https://www.88kanqiu.tw",
        "https://www.88zhibo.me",
    )

    /** 官方地址发布页：主站全部不可用时从这里发现最新可用镜像 */
    val indexUrls: List<String> = listOf(
        "https://www.88kq.me/",
        "https://88kq.me/",
    )
}

/** 一场比赛 */
data class LiveMatch(
    val id: Int,
    val date: String,          // 2026-11-17
    val time: String,          // 08:00
    val league: String,        // NBA / NBA常规赛 / 英超第11轮
    val home: String,
    val away: String,
    val homeLogo: String = "",
    val awayLogo: String = "",
    val status: String = "",   // 直播中 / 已结束 / 未开始
) {
    val isLive: Boolean get() = status.contains("直播")
    val isFinished: Boolean get() = status.contains("结束")
    val group: String get() = LiveGroup.of(league)
}

/** 按日期分组 */
data class LiveDay(val date: String, val matches: List<LiveMatch>)

data class LiveHome(val days: List<LiveDay>) {
    val all: List<LiveMatch> get() = days.flatMap { it.matches }
    val liveNow: List<LiveMatch> get() = all.filter { it.isLive }
}

/** 直播筛选分组：全部/NBA/篮球/足球/其他 */
object LiveGroup {
    const val ALL = "全部"
    const val NBA = "NBA"
    const val BASKETBALL = "篮球"
    const val FOOTBALL = "足球"
    const val OTHER = "其他"

    val chips = listOf(ALL, NBA, BASKETBALL, FOOTBALL, OTHER)

    private val footballKeys = listOf(
        "英超", "西甲", "意甲", "德甲", "法甲", "欧冠", "欧联", "欧国联",
        "中超", "中甲", "亚冠", "足总杯", "美职联", "足球", "世界杯", "欧洲杯", "美洲杯",
    )
    private val basketballKeys = listOf("NBA", "CBA", "WNBA", "NCAA", "篮球", "男篮", "女篮")

    fun of(league: String): String {
        val upper = league.uppercase()
        if (upper.contains("NBA")) return NBA
        if (basketballKeys.any { upper.contains(it.uppercase()) }) return BASKETBALL
        if (footballKeys.any { league.contains(it) }) return FOOTBALL
        return OTHER
    }

    /** 录像标题没有独立联赛字段，用关键词归类 */
    fun ofTitle(title: String): String {
        val upper = title.uppercase()
        if (upper.contains("NBA")) return NBA
        if (basketballKeys.any { upper.contains(it.uppercase()) }) return BASKETBALL
        if (footballKeys.any { title.contains(it) }) return FOOTBALL
        return OTHER
    }

    fun accept(group: String, chip: String): Boolean = when (chip) {
        ALL -> true
        NBA -> group == NBA
        BASKETBALL -> group == NBA || group == BASKETBALL
        else -> group == chip
    }
}

/** 可播放线路（已从包装页解析出 m3u8 直链） */
data class LiveLink(
    val name: String,
    val url: String,
    /** 播放时建议携带的 Referer；部分 CDN 校验包装站来源 */
    val referer: String = "",
)

/** 录像列表项 */
data class ReplayItem(
    val id: Int,
    val title: String,
    val desc: String,
    val cover: String,
    val publishedAt: String,
) {
    val group: String get() = LiveGroup.ofTitle(title)
}

data class ReplayPage(val items: List<ReplayItem>, val page: Int, val totalPages: Int)

/** 录像详情：多个清晰度/线路 */
data class ReplayDetail(
    val id: Int,
    val title: String,
    val desc: String,
    val cover: String,
    val links: List<LiveLink>,
)
