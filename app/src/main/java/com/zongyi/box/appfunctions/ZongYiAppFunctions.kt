package com.zongyi.box.appfunctions

import android.content.Context
import android.content.Intent
import androidx.appfunctions.AppFunctionContext
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionElementNotFoundException
import androidx.appfunctions.service.AppFunction
import com.zongyi.box.data.HistoryEntry
import com.zongyi.box.data.LiveApi
import com.zongyi.box.data.LiveGroup
import com.zongyi.box.data.LiveMatch
import com.zongyi.box.data.ParserLive
import com.zongyi.box.data.ReplayItem
import com.zongyi.box.data.SiteApi
import com.zongyi.box.data.Src
import com.zongyi.box.data.Store
import com.zongyi.box.data.VodDetail
import com.zongyi.box.data.VodItem
import com.zongyi.box.ui.LivePlayerActivity
import com.zongyi.box.ui.LivePlayerBridge
import com.zongyi.box.ui.LivePlayerSnapshot
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

    /** 一场体育赛事（直播/未开始/已结束） */
    @AppFunctionSerializable(isDescribedByKDoc = true)
    data class LiveMatchInfo(
        /** 赛事 id：调用 [getLiveSignals]、[playLiveMatch] 时传入 */
        val matchId: Int,
        /** 比赛日期，如 2026-11-17 */
        val date: String,
        /** 开赛时间，如 08:30 */
        val time: String,
        /** 联赛名，如 NBA常规赛 */
        val league: String,
        /** 主队 */
        val home: String,
        /** 客队 */
        val away: String,
        /** 状态：直播中 / 未开始 / 已结束 */
        val status: String,
        /** 分类：NBA / 篮球 / 足球 / 其他 */
        val group: String,
        /** 当前是否正在直播 */
        val live: Boolean,
    )

    /** 赛程汇总 */
    @AppFunctionSerializable(isDescribedByKDoc = true)
    data class LiveSchedule(
        /** 正在直播的比赛 */
        val liveNow: List<LiveMatchInfo>,
        /** 全部比赛（含直播中、未开始、已结束） */
        val matches: List<LiveMatchInfo>,
    )

    /** 一条赛事录像 */
    @AppFunctionSerializable(isDescribedByKDoc = true)
    data class LiveReplayInfo(
        /** 录像 id：调用 [getLiveSignals]、[playLiveReplay] 时传入 */
        val replayId: Int,
        /** 标题，如「NBA 魔术 vs 凯尔特人 全场回放」 */
        val title: String,
        /** 简介/比分等摘要 */
        val desc: String,
        /** 封面图 url */
        val cover: String,
        /** 发布日期 */
        val publishedAt: String,
    )

    /** 录像分页结果 */
    @AppFunctionSerializable(isDescribedByKDoc = true)
    data class LiveReplayPage(
        /** 当前页码 */
        val page: Int,
        /** 总页数 */
        val totalPages: Int,
        /** 是否还有下一页 */
        val hasMore: Boolean,
        /** 本页录像 */
        val replays: List<LiveReplayInfo>,
    )

    /** 一条可播放信号（线路） */
    @AppFunctionSerializable(isDescribedByKDoc = true)
    data class LiveSignal(
        /** 线路名，如「线路C4」，调用 [playLiveMatch]/[controlLivePlayer] 的 lineName 传入 */
        val name: String,
        /**
         * 线路授权过期时间（epoch 秒）。
         * 9223372036854775807 表示不过期；当前时间已超过该值说明线路需要刷新。
         */
        val expiresAtSec: Long,
    )

    /** 信号列表 */
    @AppFunctionSerializable(isDescribedByKDoc = true)
    data class LiveSignalList(
        /** 赛事 id 或录像 id */
        val id: Int,
        /** 是否为录像 */
        val replay: Boolean,
        /** 可用线路 */
        val signals: List<LiveSignal>,
    )

    /** 拉起播放器的结果 */
    @AppFunctionSerializable(isDescribedByKDoc = true)
    data class LivePlaybackResult(
        /** 是否已拉起播放页 */
        val started: Boolean,
        /** live=直播, replay=录像回放 */
        val kind: String,
        /** 赛事 id 或录像 id */
        val id: Int,
        /** 播放标题 */
        val title: String,
        /** 使用的线路名；自动选择时为「自动」 */
        val lineName: String,
        /** 播放倍速 */
        val speed: Float,
        /** 画面比例：fit=适应, stretch=拉伸, crop=裁剪铺满 */
        val scale: String,
        /** 右侧信息栏是否显示 */
        val sidebarVisible: Boolean,
        /** 底部控制条是否启用 */
        val controlsEnabled: Boolean,
    )

    /** 播放器实时状态 */
    @AppFunctionSerializable(isDescribedByKDoc = true)
    data class LivePlayerStatus(
        /** 是否有播放页处于打开状态 */
        val open: Boolean,
        /** live=直播, replay=录像回放；未打开时为空串 */
        val mode: String,
        /** 播放标题 */
        val title: String,
        /** 是否正在播放 */
        val playing: Boolean,
        /** 是否被用户/AI 暂停 */
        val paused: Boolean,
        /** 当前倍速 */
        val speed: Float,
        /** 画面比例：fit / stretch / crop */
        val scale: String,
        /** 右侧信息栏是否显示 */
        val sidebarVisible: Boolean,
        /** 底部控制条当前是否可见 */
        val controlBarVisible: Boolean,
        /** 底部控制条是否启用（设置项） */
        val controlsEnabled: Boolean,
        /** 当前线路名 */
        val currentLine: String,
        /** 全部线路名 */
        val lines: List<String>,
        /** 当前播放位置（秒） */
        val positionSeconds: Long,
        /** 总时长（秒），直播为 0 */
        val durationSeconds: Long,
    )

    /** 播放器控制结果 */
    @AppFunctionSerializable(isDescribedByKDoc = true)
    data class LiveControlResult(
        /** 至少一项控制是否生效 */
        val applied: Boolean,
        /** 失败原因，全部成功时为空串 */
        val message: String,
        /** 控制后的播放器状态 */
        val player: LivePlayerStatus,
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

    // ---------- 体育直播 / 录像 ----------

    /**
     * 获取体育赛程（88 看球）：正在直播的比赛 + 全部比赛。
     *
     * 用户问「今天有什么比赛/正在直播什么」时调用；拿到 matchId 后可继续用
     * [getLiveSignals] 查看线路，或直接 [playLiveMatch] 播放。
     *
     * @param appFunctionContext 执行上下文（由系统提供）
     * @param onlyLive 仅返回正在直播的比赛，默认 false 返回全部
     * @param league 按联赛名模糊筛选，如「NBA」「英超」
     * @param group 按分类筛选：全部 / NBA / 篮球 / 足球 / 其他
     * @return 正在直播列表与筛选后的全部比赛
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getLiveSchedule(
        appFunctionContext: AppFunctionContext,
        onlyLive: Boolean? = false,
        league: String? = null,
        group: String? = null,
    ): LiveSchedule = withContext(Dispatchers.Default) {
        val home = LiveApi.home()
        val liveOnly = onlyLive ?: false
        val leagueQuery = league?.trim()?.takeIf { it.isNotBlank() }
        val groupChip = group?.trim()?.takeIf { it.isNotBlank() }?.let { normalizeGroup(it) }
        val matches = home.all.filter { m ->
            (!liveOnly || m.isLive) &&
                (leagueQuery == null || m.league.contains(leagueQuery, ignoreCase = true)) &&
                (groupChip == null || LiveGroup.accept(m.group, groupChip))
        }
        LiveSchedule(
            liveNow = home.liveNow.map { it.toLiveInfo() },
            matches = matches.map { it.toLiveInfo() },
        )
    }

    /**
     * 获取赛事录像列表（分页）。
     *
     * @param appFunctionContext 执行上下文（由系统提供）
     * @param page 页码，从 1 开始
     * @return 录像分页结果
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getLiveReplays(
        appFunctionContext: AppFunctionContext,
        page: Int? = 1,
    ): LiveReplayPage = withContext(Dispatchers.Default) {
        val p = (page ?: 1).coerceAtLeast(1)
        val data = LiveApi.replays(p)
        LiveReplayPage(
            page = data.page,
            totalPages = data.totalPages,
            hasMore = data.page < data.totalPages,
            replays = data.items.map { it.toReplayInfo() },
        )
    }

    /**
     * 查询某场比赛 / 某条录像的可用线路。
     *
     * 线路带时限（expiresAtSec），过期后需重新调用本功能刷新；
     * 拿到线路名后可在播放时用 lineName 指定。
     *
     * @param appFunctionContext 执行上下文（由系统提供）
     * @param id 赛事 id 或录像 id
     * @param replay 传 true 表示 id 是录像 id，默认 false 表示赛事 id
     * @return 线路名与过期时间
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getLiveSignals(
        appFunctionContext: AppFunctionContext,
        id: Int,
        replay: Boolean? = false,
    ): LiveSignalList = withContext(Dispatchers.Default) {
        if (id <= 0) throw AppFunctionInvalidArgumentException("id 必须为正整数")
        val isReplay = replay == true
        val links = if (isReplay) LiveApi.replayDetail(id).links else LiveApi.sources(id)
        LiveSignalList(
            id = id,
            replay = isReplay,
            signals = links.map { LiveSignal(it.name, ParserLive.expireAt(it.url)) },
        )
    }

    /**
     * 打开直播播放页并播放某场比赛。
     *
     * 播放页支持横屏「左画面 + 右信息栏」，可一次指定倍速、画面比例、
     * 侧栏与控制条；播放过程中还能用 [controlLivePlayer] 随时调整。
     *
     * @param appFunctionContext 执行上下文（由系统提供）
     * @param matchId 赛事 id，来自 [getLiveSchedule]
     * @param lineName 指定线路名（如「线路C4」），不传则自动选择可用线路
     * @param speed 倍速 0.5~2.0，不传沿用上次设置
     * @param scale 画面比例：fit=适应，stretch=拉伸，crop=裁剪铺满
     * @param sidebarVisible 是否显示右侧信息栏，false 则画面铺满
     * @param controlsVisible 是否启用底部控制条（单击画面显隐）
     * @return 拉起结果与实际使用的播放参数
     * @throws AppFunctionElementNotFoundException 赛事不存在时抛出
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun playLiveMatch(
        appFunctionContext: AppFunctionContext,
        matchId: Int,
        lineName: String? = null,
        speed: Float? = null,
        scale: String? = null,
        sidebarVisible: Boolean? = null,
        controlsVisible: Boolean? = null,
    ): LivePlaybackResult = withContext(Dispatchers.Default) {
        if (matchId <= 0) throw AppFunctionInvalidArgumentException("matchId 必须为正整数")
        val match = LiveApi.home().all.firstOrNull { it.id == matchId }
            ?: throw AppFunctionElementNotFoundException("未找到赛事 $matchId，请先用 getLiveSchedule 查询")
        val prefs = resolvePlaybackPrefs(speed, scale, controlsVisible)
        val title = "${match.home} vs ${match.away}"
        withContext(Dispatchers.Main) {
            appContext.startActivity(
                LivePlayerActivity.liveIntent(appContext, match).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putAiExtras(lineName, speed, scale, sidebarVisible, controlsVisible)
                }
            )
        }
        LivePlaybackResult(
            started = true,
            kind = "live",
            id = matchId,
            title = title,
            lineName = lineName?.takeIf { it.isNotBlank() } ?: "自动",
            speed = prefs.first,
            scale = prefs.second,
            sidebarVisible = sidebarVisible ?: true,
            controlsEnabled = prefs.third,
        )
    }

    /**
     * 打开录像回放。
     *
     * @param appFunctionContext 执行上下文（由系统提供）
     * @param replayId 录像 id，来自 [getLiveReplays]
     * @param lineName 指定线路名，不传则自动选择
     * @param speed 倍速 0.5~2.0，不传沿用上次设置
     * @param scale 画面比例：fit=适应，stretch=拉伸，crop=裁剪铺满
     * @param sidebarVisible 是否显示右侧信息栏
     * @param controlsVisible 是否启用底部控制条
     * @return 拉起结果与实际使用的播放参数
     * @throws AppFunctionElementNotFoundException 录像不存在时抛出
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun playLiveReplay(
        appFunctionContext: AppFunctionContext,
        replayId: Int,
        lineName: String? = null,
        speed: Float? = null,
        scale: String? = null,
        sidebarVisible: Boolean? = null,
        controlsVisible: Boolean? = null,
    ): LivePlaybackResult = withContext(Dispatchers.Default) {
        if (replayId <= 0) throw AppFunctionInvalidArgumentException("replayId 必须为正整数")
        val detail = LiveApi.replayDetail(replayId)
        if (detail.links.isEmpty()) {
            throw AppFunctionElementNotFoundException("录像「${detail.title}」暂无可用线路")
        }
        val item = ReplayItem(
            id = detail.id,
            title = detail.title,
            desc = detail.desc,
            cover = detail.cover,
            publishedAt = "",
        )
        val prefs = resolvePlaybackPrefs(speed, scale, controlsVisible)
        withContext(Dispatchers.Main) {
            appContext.startActivity(
                LivePlayerActivity.replayIntent(appContext, item).apply {
                    putAiExtras(lineName, speed, scale, sidebarVisible, controlsVisible)
                }
            )
        }
        LivePlaybackResult(
            started = true,
            kind = "replay",
            id = detail.id,
            title = detail.title,
            lineName = lineName?.takeIf { it.isNotBlank() } ?: "自动",
            speed = prefs.first,
            scale = prefs.second,
            sidebarVisible = sidebarVisible ?: true,
            controlsEnabled = prefs.third,
        )
    }

    /**
     * 查询当前播放器状态（是否打开、直播还是回放、当前线路、倍速、画面比例、
     * 侧栏/控制条显隐、播放位置等）。
     *
     * @param appFunctionContext 执行上下文（由系统提供）
     * @return 播放器状态；未打开时 open=false
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getLivePlayerStatus(
        appFunctionContext: AppFunctionContext,
    ): LivePlayerStatus = withContext(Dispatchers.Main) {
        LivePlayerBridge.current()?.aiSnapshot()?.toStatus() ?: closedLiveStatus()
    }

    /**
     * 实时控制正在播放的直播/录像：倍速、画面比例、侧栏显隐、控制条开关、
     * 切换线路、暂停/继续、回放跳转进度。只需传要变更的参数。
     *
     * 对应界面能力：右上角齿轮里的「播放速度 / 画面比例 / 控制条 / 信号线路」，
     * 以及侧栏的收起按钮。用户说「把侧边栏收起」「调成 1.5 倍速」「全屏铺满」
     * 「换一条线路」「暂停一下」时调用。
     *
     * @param appFunctionContext 执行上下文（由系统提供）
     * @param speed 倍速 0.5~2.0（0.25~4 均可）
     * @param scale 画面比例：fit=适应，stretch=拉伸，crop=裁剪铺满
     * @param sidebarVisible true 显示右侧信息栏，false 收起让画面铺满
     * @param controlsVisible 是否启用底部控制条
     * @param lineName 要切换的线路名（见 [getLivePlayerStatus].lines）
     * @param paused true 暂停，false 继续
     * @param positionPercent 回放进度百分比 0~100（直播不支持）
     * @return 控制结果与控制后的状态
     * @throws AppFunctionElementNotFoundException 当前没有打开播放页时抛出
     * @throws AppFunctionInvalidArgumentException 未传任何参数或参数非法时抛出
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun controlLivePlayer(
        appFunctionContext: AppFunctionContext,
        speed: Float? = null,
        scale: String? = null,
        sidebarVisible: Boolean? = null,
        controlsVisible: Boolean? = null,
        lineName: String? = null,
        paused: Boolean? = null,
        positionPercent: Int? = null,
    ): LiveControlResult = withContext(Dispatchers.Main) {
        val activity = LivePlayerBridge.current()
            ?: throw AppFunctionElementNotFoundException(
                "当前没有打开播放页，请先调用 playLiveMatch 或 playLiveReplay"
            )
        if (speed == null && scale == null && sidebarVisible == null &&
            controlsVisible == null && lineName == null && paused == null && positionPercent == null
        ) {
            throw AppFunctionInvalidArgumentException("请至少指定一个控制参数")
        }
        positionPercent?.let {
            if (it !in 0..100) throw AppFunctionInvalidArgumentException("positionPercent 必须在 0~100 之间")
        }
        val normalizedScale = scale?.let { normalizeScale(it) }
        val result = activity.aiControl(
            speed = speed,
            scale = normalizedScale,
            sidebarVisible = sidebarVisible,
            controlsVisible = controlsVisible,
            lineName = lineName,
            paused = paused,
            positionPercent = positionPercent,
        )
        LiveControlResult(
            applied = result.applied,
            message = result.message,
            player = result.state?.toStatus() ?: closedLiveStatus(),
        )
    }

    /**
     * 关闭当前直播/录像播放页。
     *
     * @param appFunctionContext 执行上下文（由系统提供）
     * @return 是否关闭了一个已打开的播放页
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun closeLivePlayer(
        appFunctionContext: AppFunctionContext,
    ): Boolean = withContext(Dispatchers.Main) {
        val activity = LivePlayerBridge.current() ?: return@withContext false
        activity.finish()
        true
    }

    // ---------- 直播工具 ----------

    private fun LiveMatch.toLiveInfo() = LiveMatchInfo(
        matchId = id,
        date = date,
        time = time,
        league = league,
        home = home,
        away = away,
        status = status,
        group = group,
        live = isLive,
    )

    private fun ReplayItem.toReplayInfo() = LiveReplayInfo(
        replayId = id,
        title = title,
        desc = desc,
        cover = cover,
        publishedAt = publishedAt,
    )

    private fun LivePlayerSnapshot.toStatus() = LivePlayerStatus(
        open = true,
        mode = mode,
        title = title,
        playing = playing,
        paused = paused,
        speed = speed,
        scale = scale,
        sidebarVisible = sidebarVisible,
        controlBarVisible = controlBarVisible,
        controlsEnabled = controlsEnabled,
        currentLine = currentLine,
        lines = lines,
        positionSeconds = positionMs / 1000,
        durationSeconds = durationMs / 1000,
    )

    private fun closedLiveStatus() = LivePlayerStatus(
        open = false,
        mode = "",
        title = "",
        playing = false,
        paused = false,
        speed = 1f,
        scale = "fit",
        sidebarVisible = true,
        controlBarVisible = false,
        controlsEnabled = true,
        currentLine = "",
        lines = emptyList(),
        positionSeconds = 0,
        durationSeconds = 0,
    )

    /** 实际生效的倍速 / 比例 / 控制条：显式参数优先，否则沿用播放器上次设置 */
    private fun resolvePlaybackPrefs(
        speed: Float?,
        scale: String?,
        controlsVisible: Boolean?,
    ): Triple<Float, String, Boolean> {
        val prefs = appContext.getSharedPreferences("live_player", Context.MODE_PRIVATE)
        val s = (speed ?: prefs.getFloat("speed", 1f)).coerceIn(0.25f, 4f)
        val sc = scale?.let { normalizeScale(it) }
            ?: when (prefs.getInt("scale", 0)) {
                -4 -> "stretch"
                4 -> "crop"
                else -> "fit"
            }
        return Triple(s, sc, controlsVisible ?: prefs.getBoolean("bar", true))
    }

    private fun normalizeGroup(group: String): String = when (group.trim().lowercase()) {
        "all", "全部" -> LiveGroup.ALL
        "nba" -> LiveGroup.NBA
        "basketball", "篮球" -> LiveGroup.BASKETBALL
        "football", "soccer", "足球" -> LiveGroup.FOOTBALL
        "other", "其他" -> LiveGroup.OTHER
        else -> throw AppFunctionInvalidArgumentException(
            "group 仅支持 全部/NBA/篮球/足球/其他"
        )
    }

    private fun normalizeScale(scale: String): String = when (scale.trim().lowercase()) {
        "fit", "auto", "default", "适应" -> "fit"
        "stretch", "match", "拉伸" -> "stretch"
        "crop", "full", "裁剪", "铺满" -> "crop"
        else -> throw AppFunctionInvalidArgumentException("scale 仅支持 fit/stretch/crop")
    }

    private fun Intent.putAiExtras(
        lineName: String?,
        speed: Float?,
        scale: String?,
        sidebarVisible: Boolean?,
        controlsVisible: Boolean?,
    ) {
        lineName?.takeIf { it.isNotBlank() }?.let { putExtra(LivePlayerActivity.EXTRA_AI_LINE, it) }
        speed?.let { putExtra(LivePlayerActivity.EXTRA_AI_SPEED, it) }
        scale?.let { putExtra(LivePlayerActivity.EXTRA_AI_SCALE, normalizeScale(it)) }
        sidebarVisible?.let { putExtra(LivePlayerActivity.EXTRA_AI_SIDEBAR, it) }
        controlsVisible?.let { putExtra(LivePlayerActivity.EXTRA_AI_CONTROLS, it) }
    }
}
