package com.zongyi.box.data

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 智能 DNS 解析（防运营商 DNS 污染 / DNS 故障）：
 *
 * 1. 站点主域（[dohFirstSuffixes] 及其子域名）【优先走 DoH】拿到真实 IP，
 *    避免运营商 DNS 把域名"成功"解析到假 IP（污染），导致整站打不开；
 * 2. 其他域名系统解析优先，解析失败（UnknownHostException）时走 DoH 兜底；
 * 3. DoH 双通道：阿里 223.5.5.5 → 腾讯 120.53.53.53，均以 IP 直连（无 DNS 依赖）；
 * 4. DoH 结果内存缓存 60s，避免每次请求都查询。
 */
object SmartDns : Dns {

    /** 优先走 DoH 的站点主域（含全部子域名） */
    private val dohFirstSuffixes = listOf("ijujizy.com", "5k35.top")

    private const val TTL_MS = 60_000L

    /** DoH 结果缓存：host → (IPs, 写入时间) */
    private val cache = ConcurrentHashMap<String, Pair<List<InetAddress>, Long>>()

    /** DoH 查询专用裸 client：不带自定义 Dns，避免递归 */
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    override fun lookup(hostname: String): List<InetAddress> {
        val preferDoh = dohFirstSuffixes.any { hostname == it || hostname.endsWith(".$it") }
        if (preferDoh) {
            dohLookup(hostname)?.let { return it }
        }
        return try {
            Dns.SYSTEM.lookup(hostname)
        } catch (e: UnknownHostException) {
            dohLookup(hostname) ?: throw e
        }
    }

    /** DoH 解析（阿里 → 腾讯），带 TTL 缓存；全部失败返回 null */
    private fun dohLookup(hostname: String): List<InetAddress>? {
        cache[hostname]?.let { (ips, at) ->
            if (System.currentTimeMillis() - at < TTL_MS) return ips
        }
        val ips = queryDoh("https://223.5.5.5/resolve?name=$hostname&type=1")
            ?: queryDoh("https://120.53.53.53/dns-query?name=$hostname&type=1")
            ?: return null
        if (ips.isNotEmpty()) {
            cache[hostname] = ips to System.currentTimeMillis()
            android.util.Log.i("ZongYiBox", "DoH 解析 $hostname → ${ips.joinToString() { it.hostAddress ?: "?" }}")
        }
        return ips.ifEmpty { null }
    }

    /** 单个 DoH 通道查询（JSON API），失败返回 null */
    private fun queryDoh(url: String): List<InetAddress>? = try {
        client.newCall(
            Request.Builder().url(url).header("accept", "application/dns-json").build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val answers = JSONObject(body).optJSONArray("Answer") ?: return null
            (0 until answers.length()).mapNotNull { i ->
                val a = answers.getJSONObject(i)
                if (a.optInt("type") == 1) {
                    // data 为点分 IPv4 字符串，getByName 纯数字解析不走 DNS
                    runCatching { InetAddress.getByName(a.getString("data")) }.getOrNull()
                } else null
            }.ifEmpty { null }
        }
    } catch (_: Exception) {
        null
    }
}
