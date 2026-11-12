package com.zongyi.box

import com.zongyi.box.data.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新清单解析与版本比较的单元测试。
 * 只覆盖纯逻辑（parse / isNewer / isForceUpdate / compareVersionName），不触网。
 */
class UpdateCheckerTest {

    private val base = "https://github.com/me/zongyibox-release/releases/latest/download/update.json"

    private fun release(json: String, url: String = base) =
        UpdateChecker.parse(json, url)!!

    private val sample = """
        {
          "versionCode": 3,
          "versionName": "1.2.0",
          "apk": "app-release.apk",
          "size": 18342016,
          "sha256": "ABCDEF",
          "forceBelow": 2,
          "publishedAt": "2026-11-12",
          "changelog": "· 修复黑屏\n· 新增倍速记忆"
        }
    """.trimIndent()

    @Test
    fun `parse 读取全部字段`() {
        val r = release(sample)
        assertEquals(3, r.versionCode)
        assertEquals("1.2.0", r.versionName)
        assertEquals(18342016L, r.sizeBytes)
        assertEquals("ABCDEF", r.sha256)
        assertEquals(2, r.forceBelow)
        assertEquals("· 修复黑屏\n· 新增倍速记忆", r.changelog)
        assertEquals("2026-11-12", r.publishedAt)
    }

    @Test
    fun `相对 apk 按清单目录展开并补镜像`() {
        val urls = release(sample).apkUrls
        assertTrue(urls.first().endsWith("/releases/latest/download/app-release.apk"))
        assertTrue(urls.any { it.startsWith("https://ghproxy.net/") })
        // 镜像不能重复
        assertEquals(urls.size, urls.distinct().size)
    }

    @Test
    fun `绝对 apk 地址不拼接前缀`() {
        val json = """{"versionCode":2,"apk":"https://cdn.example.com/zongyibox.apk"}"""
        assertEquals("https://cdn.example.com/zongyibox.apk", release(json).apkUrls.first())
    }

    @Test
    fun `apkUrls 数组与 apk 合并去重`() {
        val json = """
            {"versionCode":2,"apk":"a.apk","apkUrls":["b.apk","a.apk"]}
        """.trimIndent()
        // 用非 GitHub 清单地址，避免触发镜像展开，只验证合并去重
        val urls = UpdateChecker.parse(json, "https://cdn.example.com/app/update.json")!!.apkUrls
        assertEquals(2, urls.size)
        assertTrue(urls.any { it.endsWith("/a.apk") })
        assertTrue(urls.any { it.endsWith("/b.apk") })
    }

    @Test
    fun `缺字段时使用默认值`() {
        val r = release("""{"versionCode":7}""")
        assertEquals("7", r.versionName)
        assertEquals(0L, r.sizeBytes)
        assertEquals("", r.sha256)
        assertEquals(0, r.forceBelow)
        assertEquals("", r.changelog)
        assertTrue(r.apkUrls.isEmpty())
    }

    @Test
    fun `非法清单返回 null`() {
        assertNull(UpdateChecker.parse("{}", base))
        assertNull(UpdateChecker.parse("not json", base))
    }

    @Test
    fun `versionCode 更大才算新版本`() {
        val r = release(sample)
        assertTrue(UpdateChecker.isNewer(r, localCode = 2, localName = "1.1.0"))
        assertFalse(UpdateChecker.isNewer(r, localCode = 3, localName = "1.2.0"))
        assertFalse(UpdateChecker.isNewer(r, localCode = 4, localName = "1.3.0"))
    }

    @Test
    fun `versionCode 缺失时退回 versionName 比较`() {
        val json = """{"versionCode":0,"versionName":"1.10.0"}"""
        val r = release(json)
        assertTrue(UpdateChecker.isNewer(r, 0, "1.9.9"))
        assertFalse(UpdateChecker.isNewer(r, 0, "1.10.0"))
    }

    @Test
    fun `强制更新按 forceBelow 判定`() {
        val r = release(sample)
        assertTrue(UpdateChecker.isForceUpdate(r, localCode = 1))
        assertFalse(UpdateChecker.isForceUpdate(r, localCode = 2))
        // forceBelow 为 0 表示不强制
        assertFalse(UpdateChecker.isForceUpdate(release("""{"versionCode":9}"""), 1))
    }

    @Test
    fun `版本号数字比较不看字典序`() {
        assertTrue(UpdateChecker.compareVersionName("1.10", "1.9") > 0)
        assertTrue(UpdateChecker.compareVersionName("2.0", "1.99") > 0)
        assertEquals(0, UpdateChecker.compareVersionName("1.2.0", "1.2"))
        assertEquals(0, UpdateChecker.compareVersionName("v1.2", "1.2"))
        assertTrue(UpdateChecker.compareVersionName("1.2.1", "1.2") > 0)
    }

    @Test
    fun `体积格式化`() {
        assertEquals("", UpdateChecker.humanSize(0))
        assertEquals("512 KB", UpdateChecker.humanSize(512 * 1024))
        assertEquals("17.5 MB", UpdateChecker.humanSize(18342016))
    }

    @Test
    fun `清单缺少版本号时解析失败`() {
        assertNull(UpdateChecker.parse("""{"versionName":"1.0.1"}""", base))
    }

    @Test
    fun `正常清单必然带下载地址`() {
        val r = UpdateChecker.parse(sample, base)
        assertNotNull(r)
        assertTrue(r!!.apkUrls.isNotEmpty())
    }

    /**
     * 线上真实清单回归：Gitee 分发仓库的 apk 是发行版附件的绝对地址，
     * 解析后必须原样保留（不能再拼清单目录、也不该被追加镜像）。
     */
    @Test
    fun `Gitee 发行版附件地址原样保留`() {
        val json = """
            {
              "versionCode": 2,
              "versionName": "1.0.1",
              "apk": "https://gitee.com/liaz-app/zongyi-app/releases/download/v1.0.1/app-release.apk",
              "size": 16333928,
              "sha256": "9a2ba48ec1702a8cf7d2ceb67840a3d10689f09c94cb629fa996573b8a3e8441",
              "forceBelow": 0,
              "publishedAt": "2026-11-12",
              "changelog": "· 新增应用内检查更新"
            }
        """.trimIndent()
        val r = UpdateChecker.parse(
            json,
            "https://raw.giteeusercontent.com/liaz-app/zongyi-app/raw/master/update.json?_t=1"
        )!!
        assertEquals(2, r.versionCode)
        assertEquals(1, r.apkUrls.size)
        assertEquals(
            "https://gitee.com/liaz-app/zongyi-app/releases/download/v1.0.1/app-release.apk",
            r.apkUrls.first()
        )
        // 本地 versionCode 1 的用户应当收到升级提示
        assertTrue(UpdateChecker.isNewer(r, 1, "1.0.0"))
        assertFalse(UpdateChecker.isNewer(r, 2, "1.0.1"))
    }

    /** 清单 URL 带破缓存参数时，相对 apk 路径不能被参数污染 */
    @Test
    fun `破缓存参数不影响相对路径拼接`() {
        val json = """{"versionCode":3,"apk":"app-release.apk"}"""
        val r = UpdateChecker.parse(
            json,
            "https://gitee.com/me/app/raw/master/update.json?_t=1700000000"
        )!!
        assertEquals("https://gitee.com/me/app/raw/master/app-release.apk", r.apkUrls.first())
    }
}
