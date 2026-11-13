package com.zongyi.box.data

import android.content.Context
import android.content.Intent
import com.google.gson.Gson
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
 * 本地数据类功能复用 Store，播放功能拉起 PlayerActivity。
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
    )

    /** 设备上注册的完整功能集（core + local） */
    fun all(ctx: Context): List<AiFunction> = core() + local(ctx)
}
