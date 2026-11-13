package com.zongyi.box.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * 源健康检测：整站加载不了的源自动隐藏（App 内所有入口一致）。
 *
 * 规则：
 * - 连续失败 >= 2 次 → 隐藏（失败按「一次完整请求链全部域名失败」计）
 * - 任何一次成功 → 清零并解除隐藏
 * - 隐藏状态持久化；App 启动时后台探测，恢复可用则自动解除隐藏
 * - available() / availableFlow：全局 UI（首页、分类、搜索、AI 功能清单）统一以此过滤
 */
object SourceHealth {

    private const val PREFS = "source_health"
    private const val KEY_DATA = "json"

    private const val FAIL_LIMIT = 2

    private class St {
        var failures = 0
        var hidden = false
        var lastCheck = 0L
    }

    private val states = ConcurrentHashMap<String, St>()
    private var prefs: android.content.SharedPreferences? = null
    private val gson = com.google.gson.Gson()

    private val _availableFlow = MutableStateFlow(Src.entries.toList())

    /** 当前可用源（已剔除隐藏源），UI 唯一入口 */
    val availableFlow: StateFlow<List<Src>> get() = _availableFlow

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        restore()
    }

    /** 供测试注入 */
    fun initForTest(map: Map<String, Pair<Int, Boolean>>? = null) {
        states.clear()
        map?.forEach { (key, v) ->
            val st = St().apply { failures = v.first; hidden = v.second }
            states[key] = st
        }
        publish()
    }

    private fun restore() {
        val json = prefs?.getString(KEY_DATA, null) ?: return
        try {
            val map: Map<*, *> = gson.fromJson(json, Map::class.java) ?: return
            map.forEach { (key, v) ->
                val src = Src.entries.firstOrNull { it.key == key } ?: return@forEach
                if (v is Map<*, *>) {
                    states[src.key] = St().apply {
                        failures = (v["failures"] as? Double)?.toInt() ?: 0
                        hidden = v["hidden"] as? Boolean ?: false
                    }
                }
            }
            publish()
        } catch (_: Exception) {
            // 坏数据按无记录处理
        }
    }

    private fun persist() {
        val json = gson.toJson(states.entries.associate { it.key to mapOf(
            "failures" to it.value.failures,
            "hidden" to it.value.hidden,
            "lastCheck" to it.value.lastCheck,
        ) })
        prefs?.edit()?.putString(KEY_DATA, json)?.apply()
    }

    private fun publish() {
        _availableFlow.value = Src.entries.filter { !isHidden(it) }
    }

    fun isHidden(src: Src): Boolean = states[src.key]?.hidden ?: false

    fun failures(src: Src): Int = states[src.key]?.failures ?: 0

    /** 一次成功的整页加载 */
    fun markSuccess(src: Src) {
        val st = states[src.key]
        if (st == null) {
            states[src.key] = St()
        } else if (st.failures != 0 || st.hidden) {
            st.failures = 0
            st.hidden = false
        } else return
        persist()
        publish()
    }

    /** 一次完整的加载失败（该源全部候选域名均失败且无缓存兜底） */
    fun markFailure(src: Src) {
        val st = states.getOrPut(src.key) { St() }
        st.failures += 1
        if (st.failures >= FAIL_LIMIT) st.hidden = true
        persist()
        publish()
    }

    /** 探测一个源是否恢复（首页 HTML 且签名匹配） */
    suspend fun probe(src: Src): Boolean = SiteApi.probe(src)

    /**
     * 探测所有隐藏源，恢复的自动解除隐藏。
     * @return 本次恢复的源
     */
    suspend fun recheckHidden(): List<Src> {
        val hidden = Src.entries.filter { isHidden(it) }
        if (hidden.isEmpty()) return emptyList()
        val revived = hidden.filter { probe(it) }
        revived.forEach { markSuccess(it) }
        return revived
    }

    /** 当前可用源快照 */
    fun available(): List<Src> = _availableFlow.value
}
