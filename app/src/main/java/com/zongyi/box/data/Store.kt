package com.zongyi.box.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 本地存储：历史 + 收藏（Gson + 文件，轻量无数据库依赖）。
 */
object Store {

    private val gson = Gson()
    private const val FILE_HISTORY = "history.json"
    private const val FILE_FAV = "fav.json"
    private const val MAX_HISTORY = 100

    // ---------- 观看历史 ----------

    fun history(ctx: Context): MutableList<HistoryEntry> = readList(ctx, FILE_HISTORY)

    /** 写入/更新历史，同一影片保留最新记录 */
    fun saveHistory(ctx: Context, entry: HistoryEntry) {
        val list = history(ctx)
        list.removeAll { it.vodId == entry.vodId }
        list.add(0, entry)
        while (list.size > MAX_HISTORY) list.removeAt(list.size - 1)
        writeList(ctx, FILE_HISTORY, list)
    }

    /** 查询某影片的续播位置（同一线路同一集才有效） */
    fun resumeOf(ctx: Context, vodId: Int, sid: Int, nid: Int): Long {
        return history(ctx).firstOrNull { it.vodId == vodId && it.sid == sid && it.nid == nid }
            ?.positionMs ?: 0L
    }

    fun clearHistory(ctx: Context) = writeList(ctx, FILE_HISTORY, emptyList<HistoryEntry>())

    fun removeHistory(ctx: Context, vodId: Int) {
        val list = history(ctx)
        list.removeAll { it.vodId == vodId }
        writeList(ctx, FILE_HISTORY, list)
    }

    // ---------- 收藏 ----------

    fun favorites(ctx: Context): MutableList<FavEntry> = readList(ctx, FILE_FAV)

    fun isFav(ctx: Context, vodId: Int): Boolean =
        favorites(ctx).any { it.item.id == vodId }

    /** @return 操作后是否为已收藏状态 */
    fun toggleFav(ctx: Context, item: VodItem): Boolean {
        val list = favorites(ctx)
        val existed = list.removeAll { it.item.id == item.id }
        if (!existed) list.add(0, FavEntry(item, System.currentTimeMillis()))
        writeList(ctx, FILE_FAV, list)
        return !existed
    }

    // ---------- IO ----------

    private inline fun <reified T> readList(ctx: Context, file: String): MutableList<T> {
        return runCatching {
            val text = ctx.filesDir.resolve(file).takeIf { it.exists() }?.readText()
            if (text.isNullOrBlank()) mutableListOf()
            else gson.fromJson<MutableList<T>>(text, TypeToken.getParameterized(MutableList::class.java, T::class.java).type)
        }.getOrDefault(mutableListOf())
    }

    private fun writeList(ctx: Context, file: String, list: Any?) {
        runCatching {
            ctx.filesDir.resolve(file).writeText(gson.toJson(list))
        }
    }
}
