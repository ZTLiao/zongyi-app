package com.zongyi.box

import com.zongyi.box.data.DomainStore
import com.zongyi.box.data.SiteApi
import com.zongyi.box.data.Src
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 站源域名故障自动切换单元测试：
 * 主域名失败 → 自动尝试候选域名 → 内容签名校验通过后切换并持久化。
 */
class SiteApiFailoverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var mainBase: String
    private lateinit var backupBase: String
    private lateinit var storeFile: File
    private lateinit var origBase: String
    private lateinit var origBases: List<String>

    /** /main/ → 500 挂掉；/backup/ → 返回真实 555 首页 fixture；/fake/ → 下载引导页 */
    private val fixture555Home by lazy {
        javaClass.getResourceAsStream("/fixtures/fivek_home.html")!!.readBytes().decodeToString()
    }
    private val landingPage = """
        <html><head><title>555电影APP下载-永久发布页</title></head>
        <body><h1>请下载APP观看</h1></body></html>
    """.trimIndent()

    private val dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path ?: "/"
            return when {
                path.startsWith("/main/") -> MockResponse().setResponseCode(500)
                path.startsWith("/backup/") -> MockResponse().setBody(fixture555Home)
                path.startsWith("/fake/") -> MockResponse().setBody(landingPage)
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = dispatcher
        server.start()
        mainBase = server.url("/main").toString().trimEnd('/')
        backupBase = server.url("/backup").toString().trimEnd('/')
        fakeBase = server.url("/fake").toString().trimEnd('/')
        storeFile = File(tmp.root, "domains.json")
        DomainStore.init(storeFile)
        origBase = Src.DY555.base
        origBases = Src.DY555.bases
        Src.DY555.base = mainBase
        Src.DY555.bases = listOf(mainBase, backupBase)
    }

    private var fakeBase: String = ""

    @After
    fun tearDown() {
        Src.DY555.base = origBase
        Src.DY555.bases = origBases
        server.shutdown()
    }

    @Test
    fun `failover_switchesToBackup_onPrimaryFailure_andPersists`() {
        val data = runBlocking { SiteApi.home(Src.DY555) }
        // 返回了备用域名的真实内容
        assertTrue(data.sections.isNotEmpty() || data.banner.isNotEmpty())
        // 当前域名已切换
        assertEquals(backupBase, Src.DY555.base)
        // 持久化文件已记录备用域名
        val saved = storeFile.readText()
        assertTrue("持久化文件应包含备用域名: $saved", saved.contains(backupBase))
        // 下次请求直接走备用域名（不再先打主域名）
        server.takeRequest() // /main/
        runBlocking { SiteApi.home(Src.DY555) }
        assertEquals("/backup/", server.takeRequest().path)
    }

    @Test
    fun `failover_rejectsLandingPage_andKeepsOriginalBase`() {
        // 候选域名返回的是下载引导页（无内容签名）→ 拒绝切换
        Src.DY555.bases = listOf(mainBase, fakeBase)
        var thrown = false
        try {
            runBlocking { SiteApi.home(Src.DY555) }
        } catch (_: Exception) {
            thrown = true
        }
        assertTrue("全部候选失败时应抛异常", thrown)
        // 当前域名保持不变
        assertEquals(mainBase, Src.DY555.base)
    }

    @Test
    fun `failover_skipsInvalidCandidate_andUsesNext`() {
        // 主域名挂 → 候选1是引导页（拒绝）→ 候选2正常 → 切到候选2
        Src.DY555.bases = listOf(mainBase, fakeBase, backupBase)
        val data = runBlocking { SiteApi.home(Src.DY555) }
        assertTrue(data.sections.isNotEmpty() || data.banner.isNotEmpty())
        assertEquals(backupBase, Src.DY555.base)
    }

    @Test
    fun `domainStore_restoresPersistedBase_onInit`() {
        // 先模拟一次成功切换并持久化
        Src.DY555.base = backupBase
        DomainStore.record(Src.DY555)
        // 重置为初始状态，模拟 App 重启
        Src.DY555.base = mainBase
        DomainStore.init(storeFile)
        // 恢复了持久化的可用域名
        assertEquals(backupBase, Src.DY555.base)
    }

    @Test
    fun `domainStore_ignoresBaseOutsideCandidateList`() {
        // 持久化文件里的域名不在候选列表中（历史脏数据）→ 恢复时忽略
        storeFile.writeText("""{"555":"https://stale.example.com","jz":"https://stale2.example.com"}""")
        DomainStore.init(storeFile)
        assertEquals(mainBase, Src.DY555.base)
    }
}
