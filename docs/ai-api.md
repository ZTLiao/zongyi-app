# AI 接入（appfunction）接口文档

综艺盒子预留给 AI 调用的功能接口，共两条通道：

| 通道 | 规范 | 适用范围 | 状态 |
|---|---|---|---|
| **AppFunctions（Android MCP）** | Google 官方规范（`androidx.appfunctions`） | Android 16+ 设备上的系统 AI Agent（Gemini 等），进程内调用 | 随 App 安装自动注册，无需开关 |
| **HTTP 桥** | MCP 风格 REST（tools/list + tools/call） | 本机或局域网内任意 AI / 调试工具 | 需在「我的 → AI 接入」手动开启 |

---

## 通道一：AppFunctions（Google Android MCP 规范）

- 实现：`app/src/main/java/com/zongyi/box/appfunctions/ZongYiAppFunctions.kt`
- 注册：`App.onCreate` 实现 `AppFunctionConfiguration.Provider`（`androidx.appfunctions.service` 包）
- 平台要求：Android 16（API 36）+；低版本由 Jetpack 库自动降级（Agent 侧拿不到清单）
- 调用方权限：`android.permission.EXECUTE_APP_FUNCTIONS`（系统 Agent 持有）

### 功能清单

| 功能 | 说明 |
|---|---|
| `searchShows` | 按关键词搜索节目（分页） |
| `getHomeRecommendations` | 首页推荐（banner + 版块） |
| `getShowDetail` | 节目详情（简介/线路/分集） |
| `playShow` | 解析播放地址并拉起播放器（自动选线路/分集，支持断点续播） |
| `getWatchHistory` | 观看历史（含进度百分比） |
| `getFavorites` | 收藏列表 |
| `toggleFavorite` | 收藏/取消收藏 |
| `clearWatchHistory` | 清空观看历史 |

### ADB 验证

```bash
# 查看本 app 已注册的功能
adb shell cmd app_function list-app-functions --package com.zongyi.box
```

### 错误约定（Agent 可据此自我纠正）

- `AppFunctionInvalidArgumentException`：参数不合法（如 showId ≤ 0）
- `AppFunctionElementNotFoundException`：节目/线路/分集不存在

---

## 通道二：HTTP 桥（通用 MCP 风格）

在「我的 → AI 接入」开启后，app 在 `0.0.0.0:18080` 起本地 HTTP 服务，凭随机令牌鉴权。

### 端点

```
GET  /appfunction        # 功能清单（≈ MCP tools/list）
POST /appfunction/call   # 调用功能（≈ MCP tools/call）
```

### 鉴权

请求头 `X-Api-Token: <令牌>` 或 URL 参数 `?token=<令牌>`。
令牌在「我的 → AI 接入」弹窗中展示/复制，首次使用自动生成随机 UUID。

### 功能清单（`GET /appfunction`）

```json
{
  "protocol": "appfunction-mcp/1",
  "app": "ZongYiBox",
  "version": "1.0.1",
  "functions": [
    { "name": "search", "title": "搜索节目", "desc": "…",
      "params": [ {"name":"kw","required":true,"desc":"搜索关键词"}, … ] },
    …
  ]
}
```

### 调用（`POST /appfunction/call`）

```bash
curl -X POST http://<设备IP>:18080/appfunction/call \
  -H "X-Api-Token: <令牌>" -H "Content-Type: application/json" \
  -d '{"name":"search","args":{"kw":"奔跑吧"}}'
```

成功：`{"ok":true,"data":{…}}`
失败：`{"ok":false,"error":"…"}`（HTTP 401/400/404/500 对应鉴权/参数/未知功能/执行错误）

### HTTP 通道功能清单

`list_sources` `home` `search` `detail` `stream` `play` `history_list` `history_remove` `history_clear` `favorites_list` `favorite_toggle`

---

## 实现位置

| 文件 | 职责 |
|---|---|
| `data/AiFunctions.kt` | HTTP 通道功能注册表（复用 SiteApi/Store） |
| `data/AiBridge.kt` | HTTP 服务 + 协议路由（`Router.handle` 纯逻辑可单测） |
| `appfunctions/ZongYiAppFunctions.kt` | Google AppFunctions 功能实现 |
| `ui/MineFragment.kt` | 「AI 接入」开关/端点/令牌 UI |
| `test/.../AiBridgeTest.kt` | HTTP 协议单测（鉴权/清单/调用/错误映射） |
