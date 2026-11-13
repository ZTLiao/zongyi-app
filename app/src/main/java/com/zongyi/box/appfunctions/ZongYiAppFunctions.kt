package com.zongyi.box.appfunctions

import android.content.Context
import android.content.Intent
import androidx.appfunctions.AppFunctionContext
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionElementNotFoundException
import androidx.appfunctions.service.AppFunction
import com.zongyi.box.data.HistoryEntry
import com.zongyi.box.data.SiteApi
import com.zongyi.box.data.Src
import com.zongyi.box.data.Store
import com.zongyi.box.data.VodDetail
import com.zongyi.box.data.VodItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 综艺盒子 AI 功能集 —— Google Android MCP（AppFunctions）规范实现。
 *
 * Android 16+ 上由系统 AppFunctionManager 注册与索引，Gemini 等系统 AI Agent
 * 可发现并调用；Jetpack 库在不支持的设备上自动降级为不可用。
 * KDoc 会随 schema 一起暴露给 Agent，因此每个函数/参数的注释都要面向 AI 编写。
 */
class ZongYiAppFunctions(private val appContext: Context) {

    // ---------- 可序列化返回类型 ----------

    /** 节目条目（搜索/首页/收藏通用） */
    @AppFunctionSerializable(isDescribedByKDoc = true)
    data class Show(
        /** 节目 id：调用 [getShowDetail]、[playShow] 等功能时传入 */
        val showId: Int,
        /** 节目名称 */
        val title: String,
        /** 封面图 url */
        val cover: String,
        /** 更新状态，如「更新至20261111期」「已完结」 */
        val remark: String,
        /** 内容源 key：jz=综艺盒子，555=555影视 */
        val src: String,
    )

    /** 搜索结果页 */
    @AppFunctionSerializable(isDescribedByKDoc = true)
    data class SearchResultPage(
        /** 当前页码 */
        val page: Int,
        /** 总页数 */
        val totalPages: Int,
        /** 是否还有下一页 */
        val hasMore: Boolean,
        /** 本页节目列表 */
        val shows: List<Show>,
    )

    /** 单条线路及其分集 */
    @AppFunctionSerializable(isDescribedByKDoc = true)
    data class ShowSource(
        /** 线路 id：调用 [playShow] 时传入 sourceId */
        val sourceId: Int,
        /** 线路名称，如「IK在线」 */
        val name: String,
        /** 分集列表 */
        val episodes: List<Episode>,
    )

    /** 分集 */
    @AppFunctionSerializable(isDescribedByKDoc = true)
    data class Episode(
        /** 分集 id：调用 [playShow] 时传入 episodeId */
        val episodeId: Int,
        /** 分集名称，如「第20261111期」 */
        val name: String,
    )

    /** 节目详情 */
    @AppFunctionSerializable(isDescribedByKDoc = true)
    data class ShowDetail(
        /** 节目 id */
        val showId: Int,
        /** 节目名称 */
        val title: String,
        /** 封面图 url */
        val cover: String,
        /** 更新状态 */
        val remark: String,
        /** 导演 */
        val director: String,
        /** 主演 */
        val actors: String,
        /** 剧情简介 */
        val intro: String,
        /** 全部线路与分集 */
        val sources: List<ShowSource>,
    )

    /** 首页推荐 */
    @AppFunctionSerializable(isDescribedByKDoc = true)
    data class HomeSummary(
        /** 首页推荐位节目 */
        val banner: List<Show>,
        /** 各版块节目（按版块顺序拼接） */
        val sections: List<SectionShow>,
    )

    /** 版块 */
    @AppFunctionSerializable(isDescribedByKDoc = true)
    data class SectionShow(
        /** 版块名，如「热门综艺」 */
        val title: String,
        /** 版块内节目 */
        val shows: List<Show>,
    )

    /** 播放结果 */
    @AppFunctionSerializable(isDescribedByKDoc = true)
    data class PlayResult(
        /** 是否已成功拉起播放器 */
        val started: Boolean,
        /** 播放中的节目 id */
        val showId: Int,
        /** 播放使用的线路 id */
        val sourceId: Int,
        /** 播放的分集 id */
        val episodeId: Int,
        /** 播放的分集名 */
        val episodeName: String,
    )

    /** 观看历史条目 */
    @AppFunctionSerializable(isDescribedByKDoc = true)
    data class HistoryItem(
        /** 节目 id */
        val showId: Int,
        /** 节目名 */
        val title: String,
        /** 最后观看的分集名 */
        val episodeName: String,
        /** 观看进度百分比（0-100） */
        val positionPercent: Int,
        /** 最后观看时间（epoch 毫秒） */
        val updatedAtMs: Long,
        /** 内容源 key */
        val src: String,
    )

    /** 观看历史列表 */
    @AppFunctionSerializable(isDescribedByKDoc = true)
    data class HistoryList(
        /** 历史条目，最新在前 */
        val items: List<HistoryItem>,
    )

    /** 收藏列表 */
    @AppFunctionSerializable(isDescribedByKDoc = true)
    data class FavoritesList(
        /** 收藏的节目 */
        val shows: List<Show>,
    )

    /** 收藏切换结果 */
    @AppFunctionSerializable(isDescribedByKDoc = true)
    data class FavoriteResult(
        /** 操作后是否为已收藏状态 */
        val favorited: Boolean,
    )

    // ---------- 工具 ----------

    private fun VodItem.toShow() =
        Show(showId = id, title = title, cover = cover, remark = remark, src = src)

    private fun HistoryEntry.toItem(): HistoryItem {
        val percent = if (durationMs > 0) ((positionMs * 100) / durationMs).toInt().coerceIn(0, 100) else 0
        return HistoryItem(
            showId = vodId,
            title = title,
            episodeName = episodeName,
            positionPercent = percent,
            updatedAtMs = updatedAt,
            src = srcKey,
        )
    }

    private fun VodDetail.toDetail(): ShowDetail = ShowDetail(
        showId = id,
        title = title,
        cover = cover,
        remark = remark,
        director = director,
        actors = actors,
        intro = intro,
        sources = sources.map { s ->
            ShowSource(
                sourceId = s.sid,
                name = s.name,
                episodes = s.episodes.map { e -> Episode(episodeId = e.nid, name = e.name) },
            )
        },
    )

    // ---------- AI 可调用功能 ----------

    /**
     * 按关键词搜索节目。
     *
     * 当用户想找某部综艺/剧集时先用此功能查到节目，再用 [getShowDetail] 查看分集，
     * 或直接用 [playShow] 播放。
     *
     * @param appFunctionContext 执行上下文（由系统提供）
     * @param query 搜索关键词，如节目名称「奔跑吧」
     * @param src 内容源 key：jz=综艺盒子（默认），555=555影视
     * @param page 页码，从 1 开始
     * @return 分页搜索结果
     * @throws AppFunctionInvalidArgumentException query 为空时抛出
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun searchShows(
        appFunctionContext: AppFunctionContext,
        query: String,
        src: String? = "jz",
        page: Int? = 1,
    ): SearchResultPage = withContext(Dispatchers.Default) {
        if (query.isBlank()) throw AppFunctionInvalidArgumentException("query 不能为空")
        val p = SiteApi.search(Src.of(src), query.trim(), page ?: 1)
        SearchResultPage(p.page, p.totalPages, p.hasMore, p.items.map { it.toShow() })
    }

    /**
     * 获取首页推荐内容：推荐位 + 各版块节目列表，适合"有什么好看的综艺"类请求。
     *
     * @param appFunctionContext 执行上下文（由系统提供）
     * @param src 内容源 key：jz=综艺盒子（默认），555=555影视
     * @return 首页推荐数据
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getHomeRecommendations(
        appFunctionContext: AppFunctionContext,
        src: String? = "jz",
    ): HomeSummary = withContext(Dispatchers.Default) {
        val data = SiteApi.home(Src.of(src))
        HomeSummary(
            banner = data.banner.map { it.toShow() },
            sections = data.sections.map { s -> SectionShow(s.title, s.items.map { it.toShow() }) },
        )
    }

    /**
     * 获取节目详情：简介、演员、全部线路与分集列表。
     *
     * 拿到 sourceId/episodeId 后可用 [playShow] 播放指定集。
     *
     * @param appFunctionContext 执行上下文（由系统提供）
     * @param showId 节目 id（来自搜索/首页结果）
     * @param src 内容源 key，默认 jz
     * @return 节目详情
     * @throws AppFunctionElementNotFoundException 节目无可用线路时抛出
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getShowDetail(
        appFunctionContext: AppFunctionContext,
        showId: Int,
        src: String? = "jz",
    ): ShowDetail = withContext(Dispatchers.Default) {
        if (showId <= 0) throw AppFunctionInvalidArgumentException("showId 必须为正整数")
        val d = SiteApi.detail(Src.of(src), showId)
        if (d.firstAvailable() == null) throw AppFunctionElementNotFoundException("节目 ${d.title} 没有可用线路")
        d.toDetail()
    }

    /**
     * 直接播放节目：解析播放地址并在 app 播放器中开始播放。
     *
     * sourceId/episodeId 省略时自动选第一条可用线路的第一集；
     * 播放进度会记录到观看历史，支持断点续播。
     *
     * @param appFunctionContext 执行上下文（由系统提供）
     * @param showId 节目 id
     * @param src 内容源 key，默认 jz
     * @param sourceId 线路 id（来自 [getShowDetail]），省略则自动选择
     * @param episodeId 分集 id（来自 [getShowDetail]），省略则播第一集
     * @return 播放结果
     * @throws AppFunctionInvalidArgumentException 参数不合法时抛出
     * @throws AppFunctionElementNotFoundException 节目/线路/分集不存在时抛出
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun playShow(
        appFunctionContext: AppFunctionContext,
        showId: Int,
        src: String? = "jz",
        sourceId: Int? = null,
        episodeId: Int? = null,
    ): PlayResult = withContext(Dispatchers.Default) {
        if (showId <= 0) throw AppFunctionInvalidArgumentException("showId 必须为正整数")
        val source = Src.of(src)
        val d = SiteApi.detail(source, showId, SiteApi.DETAIL_TTL_MS)
        val line = sourceId?.takeIf { it > 0 }?.let { sid -> d.source(sid) } ?: d.firstAvailable()
            ?: throw AppFunctionElementNotFoundException("节目没有可用线路")
        val ep = episodeId?.takeIf { it > 0 }?.let { nid -> line.episodes.firstOrNull { it.nid == nid } }
            ?: line.episodes.firstOrNull()
            ?: throw AppFunctionElementNotFoundException("线路「${line.name}」没有可用分集")
        val stream = SiteApi.stream(source, showId, line.sid, ep.nid)
        withContext(Dispatchers.Main) {
            appContext.startActivity(
                Intent(appContext, com.zongyi.box.ui.PlayerActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("id", showId)
                    putExtra("sid", line.sid)
                    putExtra("nid", ep.nid)
                    putExtra("title", d.title)
                    putExtra("src", source.key)
                    putExtra("cover", d.cover)
                    putExtra("episodeName", ep.name)
                }
            )
        }
        PlayResult(true, showId, line.sid, ep.nid, ep.name)
    }

    /**
     * 获取观看历史（含进度百分比），适合"我上次看到第几集"类请求。
     *
     * @param appFunctionContext 执行上下文（由系统提供）
     * @return 历史列表，最新在前
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getWatchHistory(
        appFunctionContext: AppFunctionContext,
    ): HistoryList = HistoryList(Store.history(appContext).map { it.toItem() })

    /**
     * 获取收藏列表。
     *
     * @param appFunctionContext 执行上下文（由系统提供）
     * @return 收藏的节目
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getFavorites(
        appFunctionContext: AppFunctionContext,
    ): FavoritesList = FavoritesList(Store.favorites(appContext).map { it.item.toShow() })

    /**
     * 收藏或取消收藏节目（幂等切换），返回操作后的收藏状态。
     *
     * @param appFunctionContext 执行上下文（由系统提供）
     * @param showId 节目 id
     * @param title 节目名（收藏展示用）
     * @param src 内容源 key，默认 jz
     * @param cover 封面 url（可选）
     * @param remark 更新状态（可选）
     * @return 操作后是否为已收藏
     * @throws AppFunctionInvalidArgumentException showId/title 不合法时抛出
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun toggleFavorite(
        appFunctionContext: AppFunctionContext,
        showId: Int,
        title: String,
        src: String? = "jz",
        cover: String? = null,
        remark: String? = null,
    ): FavoriteResult {
        if (showId <= 0) throw AppFunctionInvalidArgumentException("showId 必须为正整数")
        if (title.isBlank()) throw AppFunctionInvalidArgumentException("title 不能为空")
        val fav = Store.toggleFav(
            appContext,
            VodItem(id = showId, title = title, cover = cover.orEmpty(), remark = remark.orEmpty(), src = src ?: Src.JUZI.key),
        )
        return FavoriteResult(fav)
    }

    /**
     * 清空全部观看历史。
     *
     * @param appFunctionContext 执行上下文（由系统提供）
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun clearWatchHistory(
        appFunctionContext: AppFunctionContext,
    ) {
        Store.clearHistory(appContext)
    }
}
