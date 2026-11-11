# m.ijujizy.com（综艺TV）站点逆向侦察记录

> 站点内核：苹果CMS（MacCMS）v10，wap 模板 mzytv。
> 侦察日期：2026-11-10。所有请求仅需移动端 UA，无反爬、无 Referer 校验。

## 站点 URL 地图

| 功能 | URL | 说明 |
|---|---|---|
| 首页 | `/` | Banner + 各版块（更新/大陆/港台/日韩/欧美/资讯） |
| 分类列表 | `/vrshow/{cat}--{sort}-----{letter}--{page}--{year}.html` | 12 段短横线分隔的筛选槽 |
| 详情页 | `/vrdetail/{id}.html` | 含全部线路×分集 |
| 播放页 | `/vrplay/{id}-{sid}-{nid}.html` | sid=线路编号, nid=集数 |
| 搜索 | `/vrsearch/{kw}-------------.html` | kw 之后 13 个 `-` |

### vrshow 12 段槽位（以 `1--------2---` 第2页 推得）

1. 分类 id（1=大陆 2=港台 3=日韩 4=欧美）
2. class（类型，未用）
3. 排序：`time`(按更新) / `hits`(按人气) / `score`(按评分)
4~5. 空
6. 首字母 A-Z / `0-9`
7~8. 空
9. 页码（从 1 开始；页码存在于 `.navigation` 区块，`尾页`链接含最大页码）
10~11. 空
12. 年份（如 2026）

## 播放地址提取（核心）

播放页 HTML 内嵌 JSON（无需执行 JS）：

```html
<script type="text/javascript">var player_aaaa={...}</script>
```

字段：
- `url`: m3u8 直链（如 `https://v.lzcdn28.com/20261110/18054_91fd082b/index.m3u8`）
- `from`: 线路标识（lzm3u8/ffm3u8/…）
- `encrypt`: 0=明文；1=base64；2=base64后反转字符串（需兼容）
- `id` / `sid` / `nid`: 影片id / 线路id / 集数id
- `vod_data`: 名称/主演/导演/分类
- `link_next` / `link_pre`: 上下集

m3u8 为 master playlist，含清晰度子清单（如 `2000k/hls/index.m3u8`），
ExoPlayer 可直接原生做清晰度切换。CDN 无 Referer 校验（裸 curl 已验证 200）。

## 详情页解析要点

- 线路：`.m-detail-playfrom-list li`（title=线路名, data-target=`m-detail-play-panel-{sid}`）
- 分集：`#m-detail-play-panel-{sid} .jjtv-content__playlist a`（href=`/vrplay/{id}-{sid}-{nid}.html`，text=集名）
- 简介：`.albumDetailIntroTxt` 的 `data-content` 属性（比内文干净）
- 状态/更新时间：`ul.desc li` 中含"状态"的行
- 封面：`.albumDetailImg img` src（相对路径 `/upload/...` 需拼 `https://m.ijujizy.com`）

## 列表/搜索/首页通用解析要点

- 条目：`li.m-item`，内含 `a.thumb`（href=/vrdetail/{id}.html, title=名称）、
  `div.quic` 的 `data-original`（封面）、`.icon-br span.label`（更新状态）、
  `h3.stitle a`（名称）、`p.des`（主演）
- 分页：`.navigation` 中 `尾页` 链接解析最大页码；无 `.navigation` = 单页
- 首页版块：`section.mod`，标题 `.mod-head-name`，条目同上；banner 为 `.banner .swiper-slide`
- 搜索空结果：文本「暂无相关内容」

## 线路清单（13条）

LZ在线(1)、FF在线(12)、IK在线(13)、YZ在线(2)、BF在线(3)、HH在线(4)、
IQY在线(6)、RY在线(7)、XG在线(8)、JS在线(9)、MD在线(5)、DYTT在线(10)、SLL在线(11)

注意：各线路分集数可能不一致（某线路更新滞后）。

## 抓样存档

recon/ 下：home.html, list1.html, show1.html, detail.html, play1.html(LZ), play12.html(FF), s2.html(搜索"奔跑")
