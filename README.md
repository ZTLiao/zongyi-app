# 综艺盒子（ZongYiBox）

综艺追更/播放 Android 客户端。纯原生 Kotlin 实现，无 WebView、无第三方跳转。

## 技术栈

- **语言**：Kotlin 2.0 / AGP 8.7.3 / Gradle 8.14.2
- **UI**：View 体系 + ViewBinding（Material 3 风格暗色主题）
- **播放**：GSYVideoPlayer 14.0（Media3/Exo 内核），原生 HLS 解码渲染
- **网络/解析**：OkHttp + Jsoup（HTML 结构化解析，不执行站点脚本）
- **存储**：Gson + 文件（历史/收藏，轻量零依赖）
- **广告**：Adsterra Direct Link（SmartLink，纯 WebView 接入，无第三方 SDK），开屏全屏广告 + 详情页底部 banner，收益支持 USDT 提现

## 功能

| 模块 | 说明 |
|---|---|
| 首页 | Banner 轮播 + 更新/大陆/港台/日韩/欧美版块 |
| 分类 | 4 分类 × 排序（更新/人气/评分）× 年份筛选 + 无限滚动分页 |
| 搜索 | 关键词搜索 + 分页 |
| 详情 | 简介出演 + 13 条线路切换 + 每线路分集列表 |
| 播放器 | 原生 ExoPlayer：HLS 流解析、清晰度切换、多线路切换、上下集/选集、倍速(0.5~3x)、亮度/音量/快进手势、双击暂停、断点续播、观看历史 |
| 我的 | 观看历史（含进度百分比）、收藏 |

## 构建

```bash
# 需要 JDK 17 与 Android SDK（local.properties 指向 sdk.dir）
./tools/gradle-8.14.2/bin/gradle assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

## 回归测试

解析器单元测试基于真实页面快照（`app/src/test/resources/fixtures/`，抓取日期 2026-11-10）：

```bash
./tools/gradle-8.14.2/bin/gradle test
```

站点模板变更导致解析失败时：重新抓取快照更新 fixtures，再修 Parser。

## 站点逆向记录

见 `docs/recon.md`：全站 URL 地图、vrshow 12 槽位筛选参数、player_aaaa m3u8 提取（encrypt 0/1/2 兼容）、各页面 DOM 解析要点。

## 已知边界

- **广告配置**：在 https://publishers.adsterra.com 后台创建 Direct Link（SmartLink），把链接填入 `app/src/main/java/com/zongyi/box/ads/AdManager.kt` 的 `SMARTLINK_URL`（开屏 + 详情页 banner 共用）；若日后拿到正式 Banner 广告标签，填入 `BANNER_TAG_URL` 可让详情页横幅切换为 WebView 渲染标签。收款方式选 USDT 加密提现（TRC20/ERC20/BEP20），最低提现 $100。**注**：Adsterra 落地页常为 http 明文且域名随广告计划变化，故 `usesCleartextTraffic` 与 `network_security_config` 均放开明文（本 App 不传敏感数据，风险可控）
- 各线路分集数可能不一致（某线路更新滞后），App 已按线路独立展示分集数
- 播放失败优先切换线路（站点线路源可用性波动大）
- 首次播放需请求播放页提取 m3u8 直链，线路切换/选集会重新请求
