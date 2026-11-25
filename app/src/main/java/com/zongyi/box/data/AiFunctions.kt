package com.zongyi.box.data

import android.content.Context
import android.content.Intent
import com.google.gson.Gson
import com.zongyi.box.ui.LivePlayerActivity
import com.zongyi.box.ui.LivePlayerBridge
import com.zongyi.box.ui.LivePlayerSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * appfunction —— 预留给 AI 接入的功能接口层。
 *
 * 每个功能 = 名称 + 描述 + 参数说明 + 挂起执行器，
 * 通过 AiBridge（本地 HTTP，MCP tools/call 风格协议）对外暴露：
 *   GET  /appfunction       → 功能清单（等价于 MCP tools/list）
 *   POST /appfunction/call  → {name, args}（等价于 MCP tools/call）
 *
 * 网络类功能直接复用 SiteApi（含域名故障切换/缓存/广告过滤），
 * 本地数据类功能复用 Store，播放功能拉起 PlayerActivity；
 * 体育直播复用 LiveApi 并拉起 LivePlayerActivity，播放中控制经 LivePlayerBridge 直达当前播放页。
 */
class AiFunction(
    val name: String,
    val title: String,
    val desc: String,
    val params: List<Param> = emptyList(),
    val handler: suspend (JSONObject) -> JSONObject,
) {
    data class Param(val name: String, val required: Boolean, val desc: String)

    /** 功能元信息（功能清单用，不含执行器） */
    fun meta(): JSONObject = JSONObject().apply {
        put("name", name)
        put("title", title)
        put("desc", desc)
        put("params", JSONArray().apply {
            params.forEach { p ->
                put(JSONObject().apply {
                    put("name", p.name)
                    put("required", p.required)
                    put("desc", p.desc)
                })
            }
        })
    }
}

object AiFunctions {

    private val gson = Gson()

    // ---------- 模型序列化 ----------

    private fun VodItem.json(): JSONObject = JSONObject(gson.toJson(this))
    private fun PageData.json(): JSONObject = JSONObject().apply {
        put("page", page)
        put("totalPages", totalPages)
        put("hasMore", hasMore)
        put("items", JSONArray().apply { items.forEach { put(it.json()) } })
    }

    private fun VodDetail.json(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("cover", cover)
        put("remark", remark)
        put("director", director)
        put("actors", actors)
        put("status", status)
        put("intro", intro)
        put("sources", JSONArray().apply {
            sources.forEach { s ->
                put(JSONObject().apply {
                    put("sid", s.sid)
                    put("name", s.name)
                    put("episodes", JSONArray().apply {
                        s.episodes.forEach { e ->
                            put(JSONObject().apply {
                                put("nid", e.nid)
                                put("name", e.name)
                            })
                        }
                    })
                })
            }
        })
    }

    private fun HistoryEntry.json(): JSONObject = JSONObject(gson.toJson(this)).apply {
        put("srcKey", srcKey)
    }

    // ---------- 注册表 ----------

    /**
     * 网络内容类功能（无需 Context，可在 JVM 单测中构建/校验元信息）。
     * 注意：handler 仅在被实际调用时才访问网络。
     */
    fun core(): List<AiFunction> = listOf(
        AiFunction(
            "list_sources", "内容源列表",
            "返回 app 聚合的全部内容源（key 用于其他功能传参）",
        ) {
            JSONObject().apply {
                put("sources", JSONArray().apply {
                    SourceHealth.available().forEach { s -> put(JSONObject().apply { put("key", s.key); put("label", s.label) }) }
                })
            }
        },
        AiFunction(
            "home", "首页推荐",
            "获取内容源首页（banner + 各版块节目列表），走 app 缓存，秒回",
            listOf(AiFunction.Param("src", false, "内容源 key（见 list_sources），默认 jz")),
        ) { args ->
            val data = SiteApi.home(Src.of(args.optString("src", Src.JUZI.key)))
            JSONObject().apply {
                put("banner", JSONArray().apply { data.banner.forEach { put(it.json()) } })
                put("sections", JSONArray().apply {
                    data.sections.forEach { s ->
                        put(JSONObject().apply {
                            put("title", s.title)
                            put("items", JSONArray().apply { s.items.forEach { put(it.json()) } })
                        })
                    }
                })
            }
        },
        AiFunction(
            "search", "搜索节目",
            "按关键词搜索节目，支持分页",
            listOf(
                AiFunction.Param("kw", true, "搜索关键词"),
                AiFunction.Param("src", false, "内容源 key，默认 jz"),
                AiFunction.Param("page", false, "页码，从 1 开始，默认 1"),
            ),
        ) { args ->
            val kw = args.optString("kw").trim()
            if (kw.isEmpty()) throw IllegalArgumentException("kw 不能为空")
            val page = args.optInt("page", 1)
            SiteApi.search(Src.of(args.optString("src", Src.JUZI.key)), kw, page).json()
        },
        AiFunction(
            "detail", "节目详情",
            "获取节目详情：简介、演员、全部线路与分集列表",
            listOf(
                AiFunction.Param("id", true, "节目 id"),
                AiFunction.Param("src", false, "内容源 key，默认 jz"),
            ),
        ) { args ->
            val id = args.optInt("id", -1)
            if (id <= 0) throw IllegalArgumentException("id 必须为正整数")
            SiteApi.detail(Src.of(args.optString("src", Src.JUZI.key)), id).json()
        },
        AiFunction(
            "stream", "解析播放地址",
            "解析指定线路/分集的 m3u8 播放直链（已过滤广告分片）",
            listOf(
                AiFunction.Param("id", true, "节目 id"),
                AiFunction.Param("sid", true, "线路 id（detail 返回）"),
                AiFunction.Param("nid", true, "分集 id（detail 返回）"),
                AiFunction.Param("src", false, "内容源 key，默认 jz"),
            ),
        ) { args ->
            val id = args.optInt("id", -1)
            val sid = args.optInt("sid", -1)
            val nid = args.optInt("nid", -1)
            if (id <= 0 || sid < 0 || nid < 0) throw IllegalArgumentException("id/sid/nid 参数不完整")
            val info = SiteApi.stream(Src.of(args.optString("src", Src.JUZI.key)), id, sid, nid)
            JSONObject().apply {
                put("url", info.url)
                put("from", info.from)
                put("nid", info.nid)
                put("prevNid", info.prevNid ?: JSONObject.NULL)
                put("nextNid", info.nextNid ?: JSONObject.NULL)
            }
        },
    )

    /**
     * 本地数据 + 播放类功能（需要 Context，仅在设备上注册）。
     */
    fun local(ctx: Context): List<AiFunction> = listOf(
        AiFunction(
            "play", "播放节目",
            "解析播放地址并直接拉起 app 播放器播放。sid/nid 省略时自动选第一条线路的第一集",
            listOf(
                AiFunction.Param("id", true, "节目 id"),
                AiFunction.Param("src", false, "内容源 key，默认 jz"),
                AiFunction.Param("sid", false, "线路 id，默认第一条可用线路"),
                AiFunction.Param("nid", false, "分集 id，默认该线路第一集"),
                AiFunction.Param("title", false, "节目名（可选，用于播放器标题与历史记录）"),
            ),
        ) { args ->
            val src = Src.of(args.optString("src", Src.JUZI.key))
            val id = args.optInt("id", -1)
            if (id <= 0) throw IllegalArgumentException("id 必须为正整数")
            val d = SiteApi.detail(src, id, SiteApi.DETAIL_TTL_MS)
            val source = args.takeIf { it.has("sid") }?.let { d.source(it.optInt("sid")) } ?: d.firstAvailable()
                ?: throw IllegalArgumentException("该节目没有可用线路")
            val ep = args.takeIf { it.has("nid") }?.let { source.episodes.firstOrNull { e -> e.nid == it.optInt("nid") } }
                ?: source.episodes.firstOrNull()
                ?: throw IllegalArgumentException("该线路没有可用分集")
            val stream = SiteApi.stream(src, id, source.sid, ep.nid)
            val title = args.optString("title", d.title)
            withContext(Dispatchers.Main) {
                ctx.startActivity(Intent(ctx, com.zongyi.box.ui.PlayerActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("id", id)
                    putExtra("sid", source.sid)
                    putExtra("nid", ep.nid)
                    putExtra("title", title)
                    putExtra("src", src.key)
                    putExtra("cover", d.cover)
                    putExtra("episodeName", ep.name)
                })
            }
            JSONObject().apply {
                put("started", true)
                put("url", stream.url)
                put("sid", source.sid)
                put("nid", ep.nid)
                put("episode", ep.name)
            }
        },
        AiFunction(
            "history_list", "观看历史",
            "获取全部观看历史（含进度百分比），最新在前",
        ) {
            JSONObject().apply {
                put("items", JSONArray().apply { Store.history(ctx).forEach { put(it.json()) } })
            }
        },
        AiFunction(
            "history_remove", "删除历史",
            "删除指定节目的观看历史",
            listOf(AiFunction.Param("vodId", true, "节目 id")),
        ) { args ->
            val vodId = args.optInt("vodId", -1)
            if (vodId <= 0) throw IllegalArgumentException("vodId 必须为正整数")
            Store.removeHistory(ctx, vodId, args.optString("srcKey", Src.JUZI.key))
            JSONObject().put("removed", true)
        },
        AiFunction(
            "history_clear", "清空历史",
            "清空全部观看历史",
        ) {
            Store.clearHistory(ctx)
            JSONObject().put("cleared", true)
        },
        AiFunction(
            "favorites_list", "收藏列表",
            "获取全部收藏节目",
        ) {
            JSONObject().apply {
                put("items", JSONArray().apply { Store.favorites(ctx).forEach { put(it.item.json()) } })
            }
        },
        AiFunction(
            "favorite_toggle", "收藏/取消收藏",
            "切换指定节目的收藏状态，返回操作后的状态",
            listOf(
                AiFunction.Param("id", true, "节目 id"),
                AiFunction.Param("title", true, "节目名"),
                AiFunction.Param("src", false, "内容源 key，默认 jz"),
                AiFunction.Param("cover", false, "封面 url"),
                AiFunction.Param("remark", false, "备注（更新状态等）"),
            ),
        ) { args ->
            val id = args.optInt("id", -1)
            if (id <= 0) throw IllegalArgumentException("id 必须为正整数")
            val title = args.optString("title")
            if (title.isEmpty()) throw IllegalArgumentException("title 不能为空")
            val fav = Store.toggleFav(
                ctx,
                VodItem(
                    id = id,
                    title = title,
                    cover = args.optString("cover"),
                    remark = args.optString("remark"),
                    src = args.optString("src", Src.JUZI.key),
                )
            )
            JSONObject().put("favorited", fav)
        },
        // ---------- 体育直播 / 录像（88 看球） ----------
        AiFunction(
            "live_schedule", "体育赛程",
            "获取体育赛程：正在直播的比赛与全部比赛（NBA/篮球/足球）。onlyLive=true 只看直播中，" +
                "league 可模糊筛选联赛，group 取 全部/NBA/篮球/足球/其他",
            listOf(
                AiFunction.Param("onlyLive", false, "true 仅返回正在直播的比赛"),
                AiFunction.Param("league", false, "联赛名关键词，如 NBA、英超"),
                AiFunction.Param("group", false, "分类：全部/NBA/篮球/足球/其他"),
            ),
        ) { args ->
            val home = LiveApi.home()
            val liveOnly = args.optBoolean("onlyLive", false)
            val league = args.optString("league").trim()
            val groupChip = args.optString("group").trim().takeIf { it.isNotEmpty() }?.let { groupName(it) }
            val matches = home.all.filter { m ->
                (!liveOnly || m.isLive) &&
                    (league.isEmpty() || m.league.contains(league, ignoreCase = true)) &&
                    (groupChip == null || LiveGroup.accept(m.group, groupChip))
            }
            JSONObject().apply {
                put("liveNow", JSONArray().apply { home.liveNow.forEach { put(it.json()) } })
                put("matches", JSONArray().apply { matches.forEach { put(it.json()) } })
            }
        },
        AiFunction(
            "live_replays", "赛事录像",
            "获取赛事录像列表（分页），拿到 replayId 后可查线路或直接播放",
            listOf(AiFunction.Param("page", false, "页码，从 1 开始，默认 1")),
        ) { args ->
            val p = args.optInt("page", 1).coerceAtLeast(1)
            val data = LiveApi.replays(p)
            JSONObject().apply {
                put("page", data.page)
                put("totalPages", data.totalPages)
                put("hasMore", data.page < data.totalPages)
                put("items", JSONArray().apply { data.items.forEach { put(it.json()) } })
            }
        },
        AiFunction(
            "live_signals", "直播/录像线路",
            "查询某场比赛或录像的可用播放线路（含授权过期时间）；线路过期后重新调用即可刷新",
            listOf(
                AiFunction.Param("id", true, "赛事 id 或录像 id"),
                AiFunction.Param("replay", false, "true 表示 id 是录像 id，默认 false"),
            ),
        ) { args ->
            val id = args.optInt("id", -1)
            if (id <= 0) throw IllegalArgumentException("id 必须为正整数")
            val isReplay = args.optBoolean("replay", false)
            val links = if (isReplay) LiveApi.replayDetail(id).links else LiveApi.sources(id)
            JSONObject().apply {
                put("id", id)
                put("replay", isReplay)
                put("signals", JSONArray().apply {
                    links.forEach { link ->
                        put(JSONObject().apply {
                            put("name", link.name)
                            put("expiresAtSec", ParserLive.expireAt(link.url))
                        })
                    }
                })
            }
        },
        AiFunction(
            "live_play_match", "播放直播",
            "拉起直播播放页播放指定比赛，可同时指定倍速、画面比例、侧栏与控制条；播放中可用 live_control 调整",
            listOf(
                AiFunction.Param("matchId", true, "赛事 id（live_schedule 返回）"),
                AiFunction.Param("line", false, "线路名，如 线路C4，默认自动选择"),
                AiFunction.Param("speed", false, "倍速 0.5~2.0"),
                AiFunction.Param("scale", false, "画面比例：fit=适应 / stretch=拉伸 / crop=裁剪铺满"),
                AiFunction.Param("sidebar", false, "true 显示右侧信息栏，false 收起让画面铺满"),
                AiFunction.Param("controls", false, "true/false 是否启用底部控制条"),
            ),
        ) { args ->
            val matchId = args.optInt("matchId", -1)
            if (matchId <= 0) throw IllegalArgumentException("matchId 必须为正整数")
            val match = LiveApi.home().all.firstOrNull { it.id == matchId }
                ?: throw IllegalArgumentException("未找到赛事 $matchId，请先用 live_schedule 查询")
            val opts = PlaybackOptions.from(args)
            withContext(Dispatchers.Main) {
                ctx.startActivity(LivePlayerActivity.liveIntent(ctx, match).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putAiOptions(opts)
                })
            }
            playbackResult(ctx, "live", matchId, "${match.home} vs ${match.away}", opts)
        },
        AiFunction(
            "live_play_replay", "播放录像",
            "拉起播放页播放赛事录像，可同时指定倍速、画面比例、侧栏与控制条",
            listOf(
                AiFunction.Param("replayId", true, "录像 id（live_replays 返回）"),
                AiFunction.Param("line", false, "线路名，默认自动选择"),
                AiFunction.Param("speed", false, "倍速 0.5~2.0"),
                AiFunction.Param("scale", false, "画面比例：fit=适应 / stretch=拉伸 / crop=裁剪铺满"),
                AiFunction.Param("sidebar", false, "true 显示右侧信息栏，false 收起让画面铺满"),
                AiFunction.Param("controls", false, "true/false 是否启用底部控制条"),
            ),
        ) { args ->
            val replayId = args.optInt("replayId", -1)
            if (replayId <= 0) throw IllegalArgumentException("replayId 必须为正整数")
            val detail = LiveApi.replayDetail(replayId)
            if (detail.links.isEmpty()) throw IllegalArgumentException("录像「${detail.title}」暂无可用线路")
            val item = ReplayItem(detail.id, detail.title, detail.desc, detail.cover, "")
            val opts = PlaybackOptions.from(args)
            withContext(Dispatchers.Main) {
                ctx.startActivity(LivePlayerActivity.replayIntent(ctx, item).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putAiOptions(opts)
                })
            }
            playbackResult(ctx, "replay", detail.id, detail.title, opts)
        },
        AiFunction(
            "live_player_status", "播放器状态",
            "查询当前直播/录像播放页的状态：是否播放、当前线路、倍速、画面比例、侧栏与控制条显隐、播放进度",
        ) {
            val snapshot = withContext(Dispatchers.Main) { LivePlayerBridge.current()?.aiSnapshot() }
            snapshot?.json() ?: JSONObject().put("open", false)
        },
        AiFunction(
            "live_control", "控制播放器",
            "控制正在播放的直播/录像：倍速、画面比例、侧栏显隐、控制条开关、切换线路、暂停/继续、回放跳转进度。" +
                "只传需要变更的参数；侧栏收起对应界面右上角「‹」",
            listOf(
                AiFunction.Param("speed", false, "倍速 0.5~2.0"),
                AiFunction.Param("scale", false, "画面比例：fit / stretch / crop"),
                AiFunction.Param("sidebar", false, "true 显示右侧信息栏，false 收起铺满"),
                AiFunction.Param("controls", false, "true/false 是否启用底部控制条"),
                AiFunction.Param("line", false, "线路名（见 live_player_status.lines）"),
                AiFunction.Param("paused", false, "true 暂停，false 继续"),
                AiFunction.Param("positionPercent", false, "回放进度 0~100，直播无效"),
            ),
        ) { args ->
            if (args.length() == 0) throw IllegalArgumentException("请至少指定一个控制参数")
            val speed = args.takeIf { it.has("speed") }?.optDouble("speed")?.toFloat()
            val scale = args.optString("scale").trim().takeIf { it.isNotEmpty() }?.let { scaleName(it) }
            val sidebar = args.takeIf { it.has("sidebar") }?.optBoolean("sidebar")
            val controls = args.takeIf { it.has("controls") }?.optBoolean("controls")
            val line = args.optString("line").trim().takeIf { it.isNotEmpty() }
            val paused = args.takeIf { it.has("paused") }?.optBoolean("paused")
            val percent = args.takeIf { it.has("positionPercent") }?.optInt("positionPercent", -1)
                ?.takeIf { it in 0..100 }
                ?: if (args.has("positionPercent")) {
                    throw IllegalArgumentException("positionPercent 必须在 0~100 之间")
                } else null
            val result = withContext(Dispatchers.Main) {
                val activity = LivePlayerBridge.current()
                    ?: throw IllegalArgumentException("当前没有打开播放页，请先调用 live_play_match 或 live_play_replay")
                activity.aiControl(
                    speed = speed,
                    scale = scale,
                    sidebarVisible = sidebar,
                    controlsVisible = controls,
                    lineName = line,
                    paused = paused,
                    positionPercent = percent,
                )
            }
            JSONObject().apply {
                put("applied", result.applied)
                put("message", result.message)
                put("player", result.state?.json() ?: JSONObject().put("open", false))
            }
        },
        AiFunction(
            "live_close", "关闭播放器",
            "关闭当前的直播/录像播放页",
        ) {
            val closed = withContext(Dispatchers.Main) {
                val activity = LivePlayerBridge.current() ?: return@withContext false
                activity.finish()
                true
            }
            JSONObject().put("closed", closed)
        },
    )

    /** 播放参数（来自 AI 的 live_play_* 可选参数） */
    private data class PlaybackOptions(
        val line: String?,
        val speed: Float?,
        val scale: String?,
        val sidebar: Boolean?,
        val controls: Boolean?,
    ) {
        companion object {
            fun from(args: JSONObject) = PlaybackOptions(
                line = args.optString("line").trim().takeIf { it.isNotEmpty() },
                speed = args.takeIf { it.has("speed") }?.optDouble("speed")?.toFloat(),
                scale = args.optString("scale").trim().takeIf { it.isNotEmpty() }
                    ?.let { AiFunctions.scaleName(it) },
                sidebar = args.takeIf { it.has("sidebar") }?.optBoolean("sidebar"),
                controls = args.takeIf { it.has("controls") }?.optBoolean("controls"),
            )
        }
    }

    private fun Intent.putAiOptions(opts: PlaybackOptions) {
        opts.line?.let { putExtra(LivePlayerActivity.EXTRA_AI_LINE, it) }
        opts.speed?.let { putExtra(LivePlayerActivity.EXTRA_AI_SPEED, it) }
        opts.scale?.let { putExtra(LivePlayerActivity.EXTRA_AI_SCALE, it) }
        opts.sidebar?.let { putExtra(LivePlayerActivity.EXTRA_AI_SIDEBAR, it) }
        opts.controls?.let { putExtra(LivePlayerActivity.EXTRA_AI_CONTROLS, it) }
    }

    /** 返回实际生效的播放参数：显式传参优先，否则沿用播放器上次设置 */
    private fun playbackResult(
        ctx: Context,
        kind: String,
        id: Int,
        title: String,
        opts: PlaybackOptions,
    ): JSONObject {
        val prefs = ctx.getSharedPreferences("live_player", Context.MODE_PRIVATE)
        val speed = opts.speed ?: prefs.getFloat("speed", 1f)
        val scale = opts.scale ?: when (prefs.getInt("scale", 0)) {
            -4 -> "stretch"
            4 -> "crop"
            else -> "fit"
        }
        return JSONObject().apply {
            put("started", true)
            put("kind", kind)
            put("id", id)
            put("title", title)
            put("line", opts.line ?: "自动")
            put("speed", speed.toDouble())
            put("scale", scale)
            put("sidebar", opts.sidebar ?: true)
            put("controls", opts.controls ?: prefs.getBoolean("bar", true))
        }
    }

    private fun scaleName(raw: String): String = when (raw.trim().lowercase()) {
        "fit", "auto", "default", "适应" -> "fit"
        "stretch", "match", "拉伸" -> "stretch"
        "crop", "full", "裁剪", "铺满" -> "crop"
        else -> throw IllegalArgumentException("scale 仅支持 fit/stretch/crop")
    }

    private fun groupName(raw: String): String = when (raw.trim().lowercase()) {
        "all", "全部" -> LiveGroup.ALL
        "nba" -> LiveGroup.NBA
        "basketball", "篮球" -> LiveGroup.BASKETBALL
        "football", "soccer", "足球" -> LiveGroup.FOOTBALL
        "other", "其他" -> LiveGroup.OTHER
        else -> throw IllegalArgumentException("group 仅支持 全部/NBA/篮球/足球/其他")
    }

    private fun LiveMatch.json(): JSONObject = JSONObject().apply {
        put("matchId", id)
        put("date", date)
        put("time", time)
        put("league", league)
        put("home", home)
        put("away", away)
        put("status", status)
        put("group", group)
        put("live", isLive)
    }

    private fun ReplayItem.json(): JSONObject = JSONObject().apply {
        put("replayId", id)
        put("title", title)
        put("desc", desc)
        put("cover", cover)
        put("publishedAt", publishedAt)
    }

    private fun LivePlayerSnapshot.json(): JSONObject = JSONObject().apply {
        put("open", true)
        put("mode", mode)
        put("title", title)
        put("playing", playing)
        put("paused", paused)
        put("speed", speed.toDouble())
        put("scale", scale)
        put("sidebar", sidebarVisible)
        put("controlBarVisible", controlBarVisible)
        put("controls", controlsEnabled)
        put("currentLine", currentLine)
        put("lines", JSONArray().apply { lines.forEach { put(it) } })
        put("positionSeconds", positionMs / 1000)
        put("durationSeconds", durationMs / 1000)
    }

    /** 设备上注册的完整功能集（core + local） */
    fun all(ctx: Context): List<AiFunction> = core() + local(ctx)
}
