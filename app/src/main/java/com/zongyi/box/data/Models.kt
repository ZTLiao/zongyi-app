package com.zongyi.box.data

import com.google.gson.annotations.SerializedName

/** 列表条目 */
data class VodItem(
    val id: Int,
    val title: String,
    val cover: String,
    val remark: String,      // 如「更新至20261110期」「已完结」
    val actors: String = "",
    val src: String = Src.JUZI.key   // 来源站标识，兼容旧数据默认综艺盒子
)

/** 首页版块 */
data class Section(
    val title: String,
    val items: List<VodItem>
)

data class HomeData(
    val banner: List<VodItem>,
    val sections: List<Section>
)

/** 某条线路的分集 */
data class Episode(
    val nid: Int,
    val name: String
)

/** 线路 */
data class Source(
    val sid: Int,
    val name: String,
    val episodes: List<Episode>
)

/** 详情页 */
data class VodDetail(
    val id: Int,
    val title: String,
    val cover: String,
    val remark: String,
    val alias: String = "",
    val director: String = "",
    val actors: String = "",
    val status: String = "",     // 含更新时间
    val intro: String = "",
    val sources: List<Source> = emptyList()
) {
    fun source(sid: Int): Source? = sources.firstOrNull { it.sid == sid }
    fun firstAvailable(): Source? = sources.firstOrNull { it.episodes.isNotEmpty() }
}

/** 分页结果 */
data class PageData(
    val items: List<VodItem>,
    val page: Int,
    val totalPages: Int
) {
    val hasMore: Boolean get() = page < totalPages
}

/** 播放页 player_aaaa */
data class PlayerData(
    @SerializedName("url") val url: String,
    @SerializedName("encrypt") val encrypt: Int,
    @SerializedName("from") val from: String,
    @SerializedName("link_next") val linkNext: String,
    @SerializedName("link_pre") val linkPre: String,
    @SerializedName("vod_data") val vodData: VodData?
) {
    data class VodData(
        @SerializedName("vod_name") val name: String
    )
}

/** 播放信息（解析后交给播放器） */
data class StreamInfo(
    val url: String,          // m3u8 直链
    val from: String,
    val nid: Int,
    val prevNid: Int?,        // null=无上集
    val nextNid: Int?
)

/** 观看历史 */
data class HistoryEntry(
    val vodId: Int,
    val title: String,
    val cover: String,
    val sid: Int,
    val nid: Int,
    val episodeName: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val src: String? = null   // 来源站标识；旧数据为 null，视为综艺盒子
) {
    val srcKey: String get() = src ?: Src.JUZI.key
}

/** 收藏 */
data class FavEntry(
    val item: VodItem,
    val addedAt: Long
)
