package com.zongyi.box.data

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AiBridge —— appfunction 的本地 HTTP 接入服务（MCP tools 风格）。
 *
 * 协议（MCP 兼容映射：list ≈ tools/list，call ≈ tools/call）：
 *   GET  /appfunction                → 功能清单 JSON（需鉴权）
 *   POST /appfunction/call           → body {"name":"...","args":{...}}（需鉴权）
 * 鉴权：请求头 X-Api-Token 或 query 参数 ?token=，与"我的- AI 接入"里展示的令牌一致。
 *
 * 默认关闭；在"我的"页开启后监听 0.0.0.0:18080（局域网内 AI/调试工具可达），
 * 每个请求都要求令牌，且令牌为随机 UUID。
 * 路由逻辑抽为 [Router.handle] 纯函数，可在 JVM 单测中验证协议行为。
 */
object AiBridge {

    const val PREFS = "ai_bridge"
    const val KEY_ENABLED = "enabled"
    const val KEY_TOKEN = "token"
    const val KEY_PORT = "port"
    const val DEFAULT_PORT = 18080

    /** 协议版本标识：AI 侧可据此判断能力集 */
    const val PROTOCOL = "appfunction-mcp/1"

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var pool: ExecutorService? = null
    private var appContext: Context? = null

    // ---------- 开关与凭据 ----------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun token(ctx: Context): String = prefs(ctx).let {
        it.getString(KEY_TOKEN, null) ?: UUID.randomUUID().toString().also { t ->
            it.edit().putString(KEY_TOKEN, t).apply()
        }
    }

    fun port(ctx: Context): Int = prefs(ctx).getInt(KEY_PORT, DEFAULT_PORT)

    /** 开启/关闭（立即生效并持久化，App 冷启动也会按此恢复） */
    @Synchronized
    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
        if (on) start(ctx) else stop()
    }

    /** 本机局域网 IP（展示给 AI 侧配置用） */
    fun lanIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            ?.hostAddress
    }.getOrNull()

    // ---------- 服务生命周期 ----------

    @Synchronized
    fun start(ctx: Context) {
        if (running.get()) return
        appContext = ctx.applicationContext
        val registry = AiFunctions.all(ctx.applicationContext)
        val t = token(ctx)
        val p = port(ctx)
        pool = Executors.newCachedThreadPool()
        try {
            val ss = ServerSocket(p)
            serverSocket = ss
            running.set(true)
            Thread({
                while (running.get()) {
                    try {
                        val socket = ss.accept()
                        pool?.execute { serve(socket, registry, t) }
                    } catch (e: IOException) {
                        if (running.get()) continue else break
                    }
                }
            }, "AiBridge").start()
        } catch (e: Exception) {
            stop()
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        pool?.shutdown()
        pool = null
    }

    fun isRunning(): Boolean = running.get()

    private fun serve(socket: Socket, registry: List<AiFunction>, token: String) {
        try {
            socket.use { s ->
                s.soTimeout = 10_000
                val reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val pathAndQuery = parts[1]
                var contentLength = 0
                var headerToken: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        val name = line.substring(0, idx).trim().lowercase()
                        val value = line.substring(idx + 1).trim()
                        when (name) {
                            "content-length" -> contentLength = value.toIntOrNull() ?: 0
                            "x-api-token" -> headerToken = value
                        }
                    }
                }
                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = reader.read(buf, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    String(buf, 0, read)
                } else ""

                val path: String
                val query: Map<String, String>
                val qIdx = pathAndQuery.indexOf('?')
                if (qIdx >= 0) {
                    path = pathAndQuery.substring(0, qIdx)
                    query = pathAndQuery.substring(qIdx + 1).split('&')
                        .filter { it.contains('=') }
                        .associate {
                            val kv = it.split('=', limit = 2)
                            java.net.URLDecoder.decode(kv[0], "UTF-8") to
                                java.net.URLDecoder.decode(kv[1], "UTF-8")
                        }
                } else {
                    path = pathAndQuery
                    query = emptyMap()
                }

                val resp = Router.handle(registry, token, method, path, query, headerToken, body)
                val bytes = resp.body.toByteArray(Charsets.UTF_8)
                val out = s.getOutputStream()
                out.write(
                    ("HTTP/1.1 ${resp.status} ${resp.reason}\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
                )
                out.write(bytes)
                out.flush()
            }
        } catch (_: Exception) {
            // 单个请求失败不影响服务
        }
    }

    // ---------- 协议路由（纯逻辑，可单测） ----------

    object Router {
        data class Resp(val status: Int, val reason: String, val body: String) {
            companion object {
                fun json(status: Int, reason: String, obj: JSONObject) =
                    Resp(status, reason, obj.toString())
            }
        }

        fun ok(data: JSONObject) = Resp.json(200, "OK", JSONObject().put("ok", true).put("data", data))
        fun fail(status: Int, reason: String, message: String) =
            Resp.json(status, reason, JSONObject().put("ok", false).put("error", message))

        /** 功能清单（≈ MCP tools/list） */
        fun manifest(registry: List<AiFunction>, appLabel: String, version: String): JSONObject =
            JSONObject().apply {
                put("protocol", PROTOCOL)
                put("app", appLabel)
                put("version", version)
                put("functions", org.json.JSONArray().apply { registry.forEach { put(it.meta()) } })
            }

        /**
         * 处理一次请求。
         * @param registry 功能注册表
         * @param appToken 服务端令牌
         * @param queryToken url query 里的 token
         * @param headerToken 请求头 X-Api-Token
         */
        fun handle(
            registry: List<AiFunction>,
            appToken: String,
            method: String,
            path: String,
            query: Map<String, String>,
            headerToken: String?,
            body: String,
        ): Resp {
            val reqToken = headerToken?.takeIf { it.isNotBlank() } ?: query["token"]
            if (reqToken != appToken) return fail(401, "Unauthorized", "令牌缺失或不正确（X-Api-Token 或 ?token=）")

            return when {
                method == "GET" && (path == "/appfunction" || path == "/appfunction/") -> {
                    Resp.json(200, "OK", manifest(registry, "ZongYiBox", appVersion()))
                }
                method == "POST" && path == "/appfunction/call" -> {
                    val req = runCatching { JSONObject(body) }.getOrElse {
                        return fail(400, "Bad Request", "body 不是合法 JSON")
                    }
                    val name = req.optString("name")
                    if (name.isEmpty()) return fail(400, "Bad Request", "缺少 name")
                    val fn = registry.firstOrNull { it.name == name }
                        ?: return fail(404, "Not Found", "未知功能：$name（GET /appfunction 查看清单）")
                    val args = req.optJSONObject("args") ?: JSONObject()
                    val missing = fn.params.filter { it.required }.firstOrNull { !args.has(it.name) }
                    if (missing != null) return fail(400, "Bad Request", "缺少必填参数：${missing.name}")
                    return try {
                        val data = runBlocking { fn.handler(args) }
                        ok(data)
                    } catch (e: IllegalArgumentException) {
                        fail(400, "Bad Request", e.message ?: "参数错误")
                    } catch (e: Exception) {
                        fail(500, "Internal Error", "${e.javaClass.simpleName}: ${e.message}")
                    }
                }
                else -> fail(404, "Not Found", "未知路径：$method $path（可用：GET /appfunction、POST /appfunction/call）")
            }
        }

        private fun appVersion(): String = runCatching {
            Class.forName("com.zongyi.box.BuildConfig").getField("VERSION_NAME").get(null) as String
        }.getOrDefault("unknown")
    }
}
