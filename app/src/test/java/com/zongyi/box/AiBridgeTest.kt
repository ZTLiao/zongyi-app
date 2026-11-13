package com.zongyi.box

import com.zongyi.box.data.AiBridge
import com.zongyi.box.data.AiFunction
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * appfunction AI 接入桥协议测试：鉴权、清单、调用、参数校验、错误映射。
 */
class AiBridgeTest {

    private val token = "test-token"

    /** 与设备无关的测试注册表（handler 不碰网络） */
    private val registry = listOf(
        AiFunction(
            "echo", "回声", "原样返回 args",
            listOf(AiFunction.Param("msg", true, "要回显的内容")),
        ) { args -> JSONObject().put("msg", args.getString("msg")).put("extra", args.optString("extra")) },
        AiFunction("boom", "抛错", "总是抛出运行时异常") { _ ->
            throw IllegalStateException("simulate failure")
        },
        AiFunction("bad_arg", "参数错", "抛出参数错误") { _ ->
            throw IllegalArgumentException("id 必须为正整数")
        },
    )

    private fun call(body: String, reqToken: String? = token, method: String = "POST", path: String = "/appfunction/call") =
        AiBridge.Router.handle(registry, token, method, path, emptyMap(), reqToken, body)

    // ---------- 鉴权 ----------

    @Test
    fun `缺令牌返回401`() {
        val r = call("""{"name":"echo","args":{"msg":"hi"}}""", reqToken = null)
        assertEquals(401, r.status)
        assertFalse(JSONObject(r.body).getBoolean("ok"))
    }

    @Test
    fun `错误令牌返回401`() {
        val r = call("""{"name":"echo","args":{"msg":"hi"}}""", reqToken = "wrong")
        assertEquals(401, r.status)
    }

    @Test
    fun `query令牌可用`() {
        val r = AiBridge.Router.handle(
            registry, token, "GET", "/appfunction",
            mapOf("token" to token), null, "",
        )
        assertEquals(200, r.status)
    }

    // ---------- 功能清单 ----------

    @Test
    fun `清单包含协议版本与功能元信息`() {
        val r = AiBridge.Router.handle(registry, token, "GET", "/appfunction", emptyMap(), token, "")
        assertEquals(200, r.status)
        val json = JSONObject(r.body)
        assertEquals(AiBridge.PROTOCOL, json.getString("protocol"))
        val fns = json.getJSONArray("functions")
        assertEquals(registry.size, fns.length())
        val echo = fns.getJSONObject(0)
        assertEquals("echo", echo.getString("name"))
        assertEquals(true, echo.getJSONArray("params").getJSONObject(0).getBoolean("required"))
    }

    // ---------- 调用 ----------

    @Test
    fun `调用成功返回ok与数据`() {
        val r = call("""{"name":"echo","args":{"msg":"你好","extra":1}}""")
        assertEquals(200, r.status)
        val json = JSONObject(r.body)
        assertTrue(json.getBoolean("ok"))
        assertEquals("你好", json.getJSONObject("data").getString("msg"))
        assertEquals("1", json.getJSONObject("data").getString("extra"))
    }

    @Test
    fun `未知功能返回404`() {
        val r = call("""{"name":"nope","args":{}}""")
        assertEquals(404, r.status)
        assertTrue(JSONObject(r.body).getString("error").contains("nope"))
    }

    @Test
    fun `缺少必填参数返回400`() {
        val r = call("""{"name":"echo","args":{}}""")
        assertEquals(400, r.status)
        assertTrue(JSONObject(r.body).getString("error").contains("msg"))
    }

    @Test
    fun `body非JSON返回400`() {
        val r = call("not-json")
        assertEquals(400, r.status)
    }

    @Test
    fun `缺少name返回400`() {
        val r = call("""{"args":{}}""")
        assertEquals(400, r.status)
    }

    @Test
    fun `参数错误映射400`() {
        val r = call("""{"name":"bad_arg","args":{}}""")
        assertEquals(400, r.status)
        assertEquals("id 必须为正整数", JSONObject(r.body).getString("error"))
    }

    @Test
    fun `运行时异常映射500`() {
        val r = call("""{"name":"boom","args":{}}""")
        assertEquals(500, r.status)
        assertFalse(JSONObject(r.body).getBoolean("ok"))
    }

    // ---------- 路径 ----------

    @Test
    fun `未知路径返回404`() {
        val r = AiBridge.Router.handle(registry, token, "GET", "/other", emptyMap(), token, "")
        assertEquals(404, r.status)
    }
}
