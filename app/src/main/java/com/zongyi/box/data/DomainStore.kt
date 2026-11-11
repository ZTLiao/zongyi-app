package com.zongyi.box.data

import android.content.Context
import com.google.gson.Gson
import java.io.File

/**
 * 站源域名持久化：故障切换成功后记住可用域名，下次启动直接使用。
 * 存储 filesDir/data/domains.json，内容形如 {"jz":"https://...","555":"https://..."}。
 *
 * 恢复时只接受 src.bases 列表内的域名（防止历史脏数据指向无效域名）。
 */
object DomainStore {

    private var file: File? = null
    private val gson = Gson()

    fun init(context: Context) {
        init(File(File(context.filesDir, "data"), "domains.json"))
    }

    /** 供测试注入具体文件路径 */
    fun init(f: File) {
        file = f
        restore()
    }

    private fun restore() {
        val f = file ?: return
        try {
            if (!f.exists()) return
            val map: Map<*, *> = gson.fromJson(f.readText(), Map::class.java) ?: return
            map.forEach { (key, base) ->
                val src = Src.entries.firstOrNull { it.key == key } ?: return@forEach
                if (base is String && base in src.bases && src.base != base) {
                    src.base = base
                }
            }
        } catch (_: Exception) {
            // 配置文件损坏时按无记录处理
        }
    }

    /** 切换成功后记录（调用方已确认新 base 可用且属于 src.bases） */
    fun record(src: Src) {
        val f = file ?: return
        try {
            val map = Src.entries.associate { it.key to it.base }
            f.parentFile?.mkdirs()
            f.writeText(gson.toJson(map))
        } catch (_: Exception) {
            // 写失败不影响主流程
        }
    }
}
