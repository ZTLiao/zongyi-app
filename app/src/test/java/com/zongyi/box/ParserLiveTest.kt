package com.zongyi.box

import com.zongyi.box.data.LiveGroup
import com.zongyi.box.data.ParserLive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 88看球页面解析测试。
 *
 * 站点按 UA 下发两套模板（桌面 / 移动），App 使用移动 UA，
 * 这里同时覆盖两种结构，避免模板切换后直播列表静默变空。
 */
class ParserLiveTest {

    @Test
    fun parsesMobileHomeTemplate() {
        val html = """
            <ul class="list-group">
              <li class="list-group-item live-list-group-time"><span class="date"></span>2026-11-17</li>
              <li class="list-group-item group-game-item ">
                <a href='/live/131978/play'>
                  <div class="row"><div class="row">
                    <div class="col-xs-4 text-center">
                      <img src="/static/img/default.png?t=1" data-src="/static/img/nba.png" class="lazyload">
                      <span class="team-name" title="湖人">湖人</span>
                    </div>
                    <div class="col-xs-4 text-center">
                      <div>
                        <span class="center-block">NBA</span>
                        <span class="center-block game-time">08:30</span>
                        <span data-id="131978" class="center-block pay-btn">
                          <span class="btn btn-default btn-sm">直播中</span>
                        </span>
                      </div>
                    </div>
                    <div class="col-xs-4 text-center">
                      <img src="/static/img/default.png?t=1" data-src="/static/img/warriors.png" class="lazyload">
                      <span class="team-name" title="勇士">勇士</span>
                    </div>
                  </div></div>
                </a>
              </li>
            </ul>
        """.trimIndent()

        val home = ParserLive.parseHome(html)
        assertEquals(1, home.days.size)
        val match = home.all.single()
        assertEquals(131978, match.id)
        assertEquals("2026-11-17", match.date)
        assertEquals("08:30", match.time)
        assertEquals("NBA", match.league)
        assertEquals("湖人", match.home)
        assertEquals("勇士", match.away)
        assertEquals("直播中", match.status)
        assertTrue(match.isLive)
        assertTrue(match.homeLogo.endsWith("/static/img/nba.png"))
    }

    @Test
    fun parsesDesktopHomeTemplate() {
        val html = """
            <ul class="list-group">
              <li class="list-group-item live-list-group-time"><span class="date"></span>2026-11-18</li>
              <li class="list-group-item group-game-item">
                <div class="row"><div class="row">
                  <div class="col-md-1 category-game-time">09:00
                  </div>
                  <div class="col-md-3"><span class="game-type">CBA</span></div>
                  <div class="col-md-4 text-right"><span class="team-name" title="辽宁">辽宁</span></div>
                  <div class="col-xs-1"><img src="/static/img/default.png" data-src="/static/img/default.png "></div>
                  <div class="col-md-1 text-center">VS</div>
                  <div class="col-xs-1"><img src="/static/img/default.png" data-src="/static/img/default.png "></div>
                  <div class="col-md-3 text-left"><span class="team-name" title="广东">广东</span></div>
                  <div data-id="131999" class="col-md-3 pay-btn text-right">
                    <a class="btn btn-default" href='/live/131999/play'>已结束</a>
                  </div>
                </div></div>
              </li>
            </ul>
        """.trimIndent()

        val match = ParserLive.parseHome(html).all.single()
        assertEquals("09:00", match.time)
        assertEquals("CBA", match.league)
        assertEquals("已结束", match.status)
        assertTrue(match.isFinished)
        assertEquals("", match.homeLogo)
    }

    @Test
    fun parsesMobileReplayList() {
        val html = """
            <ul class="list-group">
              <a href='/replay/147725'>
                <li class="list-group-item playback-list-group-item">
                  <div class="media">
                    <div class="media-left">
                      <img class="media-object" src="/storage/images/newscover/fjcover.jpg">
                    </div>
                    <div class="media-body">
                      <h4 class="media-heading playback-list-title-item">
                        2026年11月17日 欧国联 冰岛vs卢森堡 全场录像回放
                      </h4>
                      <span>2026-11-17 09:45:54</span>
                    </div>
                  </div>
                </li>
              </a>
            </ul>
            <ul class="pagination ex-pagination">
              <li><a class="page-link" href="/replay?page=2">2</a></li>
              <li><a class="page-link" href="/replay?page=7">7</a></li>
            </ul>
        """.trimIndent()

        val page = ParserLive.parseReplayPage(html, 1)
        val item = page.items.single()
        assertEquals(147725, item.id)
        assertTrue(item.title.contains("冰岛vs卢森堡"))
        assertEquals("2026-11-17 09:45:54", item.publishedAt)
        assertEquals(7, page.totalPages)
        assertEquals(LiveGroup.FOOTBALL, item.group)
    }

    @Test
    fun parsesReplayDetailWithNestedParagraphs() {
        // 站点实际结构：容器与每条线路都用 <p>，属于非法嵌套，解析器会自动闭合外层 <p>
        val html = """
            <html><head>
              <meta name="description" content="魔术 vs 凯尔特人 全场回放">
              <title>魔术vs凯尔特人全场录像回放 - 88看球</title>
            </head><body>
              <h4 class="playback-detail-title">魔术 vs 凯尔特人 全场回放</h4>
              <p class="playback-content">
                <p><a href="https://play.88player.top/m3u8.html?url=https%3A%2F%2Fnewtc-vod123ktv.123kq.live%2Fa%2Fplaylist_eof.m3u8%3Fauth_key%3D1">[高清1] 魔术 vs 凯尔特人 全场回放</a></p>
                <p><a href="https://play.88player.top/m3u8.html?url=https%3A%2F%2Fnewtc-vod123ktv.123kq.live%2Fb%2Fplaylist_eof.m3u8">[高清2] 魔术 vs 凯尔特人 全场回放</a></p>
                <p><a href="https://v.qq.com/x/cover/abc.html">腾讯视频</a></p>
              </p>
            </body></html>
        """.trimIndent()

        val detail = ParserLive.parseReplayDetail(html, 147733)
        assertEquals("魔术 vs 凯尔特人 全场回放", detail.title)
        assertEquals(2, detail.links.size)
        assertEquals("[高清1] 魔术 vs 凯尔特人 全场回放", detail.links[0].name)
        assertEquals(
            "https://newtc-vod123ktv.123kq.live/a/playlist_eof.m3u8?auth_key=1",
            detail.links[0].url,
        )
        assertEquals("https://play.88player.top/", detail.links[0].referer)
    }

    @Test
    fun detectsExpiredAuthKey() {
        // 站点同一线路会下发旧、新两条签名，旧签名必 403
        val old = "https://play.aarray.cn/rrty/sd-1-4659374_gqzm.m3u8?auth_key=1794899322-0-0-c0a3a"
        val new = "https://play.aarray.cn/rrty/sd-1-4659374_gqzm.m3u8?auth_key=1794900131-0-0-d91bf"
        val plain = "https://hello.ooo0ooo.top/live/4668003.m3u8"

        assertEquals(1794899322L, ParserLive.expireAt(old))
        assertEquals(1794900131L, ParserLive.expireAt(new))
        assertEquals(Long.MAX_VALUE, ParserLive.expireAt(plain))

        val now = 1794900000L
        assertTrue(ParserLive.isExpired(old, now))
        assertTrue(!ParserLive.isExpired(new, now))
        assertTrue(!ParserLive.isExpired(plain, now))
    }

    @Test
    fun parsesMirrorIndexPage() {
        val html = """
            <div class="box">
              <a href="https://www.88kanqiu.la" target="_blank"><p class="text">88看球官网</p></a>
              <a href="https://down.fqzb162.com/?agent=88kanqiu&amp;app=88kanqiu" target="_blank"><p>下载APP①</p></a>
              <a href="https://www.88kanqiu.one" target="_blank"><p>备用网址④</p></a>
              <a href="https://popozhibo.com/" target="_blank"><p>其他站</p></a>
              <a href="https://www.88kanqiu.la" target="_blank"><p>重复链接</p></a>
            </div>
        """.trimIndent()

        val bases = ParserLive.parseMirrorBases(html)
        assertEquals(listOf("https://www.88kanqiu.la", "https://www.88kanqiu.one"), bases)
    }

    @Test
    fun groupsLeaguesForChips() {
        assertEquals(LiveGroup.NBA, LiveGroup.of("NBA常规赛"))
        assertEquals(LiveGroup.BASKETBALL, LiveGroup.of("CBA"))
        assertEquals(LiveGroup.FOOTBALL, LiveGroup.of("英超第11轮"))
        assertEquals(LiveGroup.OTHER, LiveGroup.of("NFL"))
        assertTrue(LiveGroup.accept(LiveGroup.NBA, LiveGroup.BASKETBALL))
        assertTrue(!LiveGroup.accept(LiveGroup.FOOTBALL, LiveGroup.NBA))
    }
}
