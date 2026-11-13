# Android AI 操作插件（App Agent）技术方案

> 目标：不修改目标 App 一行代码，用语音/文字下达意图，由本 App 代为操作其他 App。
> 示例："打开微信 → 进 KFC 小程序 → 点一个香辣鸡腿堡 → 提交订单"。

---

## 1. 结论先行

**技术可行，但必须接受三条硬边界：**

| 能力 | 可行性 | 说明 |
|---|---|---|
| 跳转类（打开 App / 指定页面 / 拉起小程序） | ★★★★★ | Intent、Deep Link、URL Scheme，确定性强 |
| 操作类（搜索、点击、滑动、填表、下单到"提交"） | ★★★☆☆ | 无障碍 + 视觉兜底，通用但脆弱，需要工程打磨 |
| 支付 / 验证码 / 人脸 | ★☆☆☆☆ | **不建议自动化**，必须人机接力（用户输密码/刷脸） |

对 KFC 这个例子，务实的目标是：

> 语音说"帮我点个香辣鸡腿堡"→ 自动打开微信 → 进入肯德基小程序 → 选门店 → 加购 → 到"确认订单"页 → **弹窗让用户确认金额并自己完成支付**。

即 **AI 负责 90% 的机械操作，最后一步交给人**。这既是合规要求，也是风控现实。

另一个必须提前说清的风险：**微信对第三方自动化非常敏感**，检测到无障碍驱动的高频/脚本化操作可能触发风控甚至封号。所以路线设计上要"能走官方通道就走官方通道，UI 自动化只做兜底"，并且优先做低风险场景验证。

---

## 2. 为什么"不改原 App 代码"能成立

Android 对外提供了 5 个"外挂面"，组合起来就能操作任意 App：

### 2.1 Intent / Deep Link / App Links
- `am start -n pkg/.Activity`、`Intent.ACTION_VIEW` + scheme
- 常见：`weixin://`、`alipays://`、`imeituan://`、`eleme://`、各家 `xxx://` 自定义 scheme
- 优点：确定性 100%，速度快
- 缺点：只能到"某个页面"，进去之后的点击还是要靠别的层

### 2.2 AccessibilityService（无障碍服务）
- 读 `AccessibilityNodeInfo` 树：拿到文本、控件类型、坐标、可点击性
- `performAction(ACTION_CLICK / ACTION_SET_TEXT / ACTION_SCROLL)`
- `dispatchGesture()` 发任意手势（API 24+）
- 监听前台包名、页面变化、Toast、弹窗
- 优点：无需 root，覆盖所有 App，能拿到语义（比纯坐标稳）
- 缺点：需要用户手动开启；部分 App（Flutter/游戏/自绘 WebView）树很稀；Android 13+ 侧载 App 还要先"允许受限设置"

### 2.3 Shizuku / ADB / Root
- Shizuku 通过 `app_process` 拿到 shell uid，可执行：
  ```sh
  am start -n com.tencent.mm/.ui.LauncherUI
  input tap 540 1980
  input swipe 540 1600 540 600 300
  uiautomator dump /sdcard/ui.xml
  ```
- 优点：不弹无障碍授权提示，稳定，可截图（`screencap`）
- 缺点：需要用户一次性配对（无线调试/电脑 adb），门槛高

### 2.4 MediaProjection + OCR / VLM（纯视觉）
- 截屏 → OCR 出文字框 → 坐标点击
- 树拿不到时（小程序 Skyline 渲染、游戏、自绘控件）的**唯一可靠兜底**
- 2025-2026 主流方案：截图 + 多模态模型直接输出 `{"action":"tap","x":..,"y":..}`

### 2.5 NotificationListenerService
- 读通知：支付结果、订单状态、验证码到达（验证码只提示不代填）
- 用于"结果回读"和任务收尾判定

**结论：主通道用「无障碍」，加速通道用「Deep Link」，兜底通道用「截图+VLM」，可选增强用「Shizuku」。**

---

## 3. 对标产品（说明赛道已验证）

| 产品 | 做法 |
|---|---|
| 智谱 AutoGLM | 云侧 Agent + 无障碍/截图操作手机，一句话点外卖、发微信 |
| Apple App Intents | 系统级，但需要 App 主动暴露能力（= 要改代码，不符合本需求） |
| 荣耀 YOYO / vivo 蓝心 / 华为小艺 | 厂商级 Agent，靠系统权限 + 自家 App 适配 |
| Operit AI（开源） | 无障碍 + Shizuku + 本地/云 LLM，可参考其架构 |
| Auto.js / Hamibot / Tasker | 脚本化无障碍，稳定但无 AI 规划 |
| AppAgent / Mobile-Agent / AutoDroid（学术） | 截图 + LLM 的 GUI Agent，验证了"纯视觉可行" |

差异化机会：**不是"能不能点"，而是"点得稳、点得快、点得便宜"**——靠"技能固化 + 失败自愈"把 LLM 从每步都调用，降到最后只在异常时调用。

---

## 4. 总体架构

```
┌──────────────────────────────────────────────────────────┐
│ 入口层  VoiceInteractionService / 悬浮球 / 磁贴 / 分享 / 通知 │
└───────────────────────────┬──────────────────────────────┘
                            ▼
┌──────────────────────────────────────────────────────────┐
│ 理解层  ASR → 意图解析 → 槽位补全 → 任务计划(Task DAG)      │
│         （云端 LLM 或端侧小模型，可插拔）                    │
└───────────────────────────┬──────────────────────────────┘
                            ▼
┌──────────────────────────────────────────────────────────┐
│ 技能层  Skill Registry：YAML/DSL 流程 + 参数 + 校验点        │
│         命中 → 直接跑；未命中 → 交给 Agent 规划              │
└───────────────────────────┬──────────────────────────────┘
                            ▼
┌──────────────────────────────────────────────────────────┐
│ 执行层  Action Runtime：launch/tap/input/scroll/wait/assert │
│         幂等 + 重试 + 超时 + 前台包名校验                    │
└──────────┬────────────────────────────────┬───────────────┘
           ▼                                ▼
┌────────────────────────┐      ┌───────────────────────────┐
│ 感知层 A：无障碍树压缩   │      │ 感知层 B：截图 + OCR/VLM    │
│ → 语义 DOM（1~3KB）     │      │ （树为空/看不懂时兜底）      │
└────────────────────────┘      └───────────────────────────┘
                            ▼
┌──────────────────────────────────────────────────────────┐
│ 安全层  包名白名单 / 敏感动作二次确认 / 日志审计 / 内容脱敏    │
└──────────────────────────────────────────────────────────┘
```

### 数据流（单步）
```
读屏 → 压缩成候选控件列表 → 决定下一步 → 执行 → 等页面稳定 → 校验是否到达预期状态
```

---

## 5. 关键设计

### 5.1 入口与唤醒

Android 不允许随便监听"你好 XX"，三种可选：

1. **成为默认数字助手**（推荐）
   - 声明 `VoiceInteractionService` + `BIND_VOICE_INTERACTION`，引导用户设为默认助手（Android 12+ 用 `RoleManager.ROLE_ASSISTANT`）
   - 之后长按 Home / 语音键 / "Hey Google" 位可拉起自己
2. **悬浮球 / 悬浮窗**：`SYSTEM_ALERT_WINDOW`，随时点一下说话，最实用
3. **快捷设置磁贴 + 桌面快捷方式 + 分享菜单**：零权限，做冷启动入口

> 现实做法：P0 先做悬浮球 + 磁贴（零门槛），P1 再补 Assistant Role。

### 5.2 感知：把 UI 树压成 LLM 能吃的"语义 DOM"

原始树动辄几千节点、几十 KB，必须裁剪：

- 只保留：可点击 / 可输入 / 有文本 / 有 contentDescription 的节点
- 过滤：不可见、面积过小、重复祖先、系统装饰
- 输出紧凑格式（给 LLM 的 prompt 里就长这样）：

```
[0] EditText  "搜索商品"        @(120,240)-(960,340)
[1] TextView  "香辣鸡腿堡"       @(80,600)-(700,680)  clickable
[2] TextView  "¥21.5"           @(760,600)-(900,680)
[3] Button    "加入购物车"       @(600,1900)-(1000,2020) clickable
[4] Button    "去结算"           @(600,2050)-(1000,2170) clickable
```

LLM 只需要回 `{"act":"tap","id":3}`，不用回坐标，稳定性高一个量级。

**视觉兜底**：当节点数 < 3 或关键文本缺失（典型：微信小程序 Skyline、Flutter、游戏）→ 截图 + OCR（PaddleOCR/MLKit 端侧）→ 仍不行 → 云侧 VLM 输出坐标。截图上传必须显式告知并可关闭。

### 5.3 决策：三级降级，控制成本与延迟

| 级别 | 触发 | 延迟 | 成本 |
|---|---|---|---|
| L1 技能直出 | 意图命中已录制技能 | 0.1~0.5s/步 | 0 |
| L2 LLM 选步 | 技能缺失 / 页面偏离 | 1~2s/步 | 低（纯文本树） |
| L3 VLM 定位 | 树不可用 / L2 连续失败 2 次 | 2~4s/步 | 高（图片） |

一个 12 步的点单流程：
- 全 L2：约 20~30s，可接受
- L1 命中：约 6~10s，体验及格线
- 全 L3：40s+，只能做兜底

所以**技能固化是产品化的关键**，LLM 是"录制器 + 修理工"，不是"每次都现场思考"。

### 5.4 执行：原子动作 + 幂等 + 校验

动作集（够用且可组合）：

```
launch(pkg | deeplink)   tap(id|text|xy)   longPress   input(text)
scroll(dir, n)           back               wait(ms|text|idle)
assert(text|id|pkg)      read(text)         screenshot
```

每个动作必须带三件套：**超时、重试、后置校验**。
- 点击后不能盲等 `sleep(1000)`，要等"页面稳定"（无障碍事件静默 300ms 或目标文本出现）
- 每次动作前校验前台包名，防止误操作到别的 App
- 写操作（下单、发消息、删除）默认不可重试，避免重复提交

### 5.5 技能 DSL + 录制 + 自愈

YAML 描述一条可复用流程，参数化：

```yaml
id: kfc.order_burger
name: 肯德基点汉堡
app:
  package: com.tencent.mm
  entry:
    type: deeplink          # 优先官方通道
    uri: "weixin://dl/business/?t=XXXX"
    fallback: ui_search     # 拿不到 scheme 时走 UI 搜索
params:
  - name: item
    ask: "要哪款汉堡？"
    default: "香辣鸡腿堡"
  - name: store
    resolve: nearest        # 定位 + 历史门店
steps:
  - assert: { anyText: ["肯德基", "KFC"], timeout: 8000 }
  - tap:    { text: "点餐", fuzzy: true }
  - tap:    { text: "{{store}}" }
  - tap:    { text: "{{item}}", scroll: auto }
  - tap:    { text: "加入购物车" }
  - assert: { anyText: ["购物车", "去结算"] }
  - tap:    { text: "去结算" }
  - assert: { anyText: ["确认订单"], timeout: 10000 }
  - confirm:
      prompt: "共 {{total}} 元，确认提交订单？"
      risk: payment
  - stop:   { reason: "请手动输入支付密码" }
onFailure:
  - screenshot: true
  - replan: { maxSteps: 5 }
```

三个能力缺一不可：
1. **录制**：用户手动走一遍，后台记录"树快照 + 动作"，自动生成 YAML（这是冷启动效率的关键）
2. **多选择器**：`text` 失败退 `contentDesc`，再退 `id`，再退"相对位置 + OCR"
3. **自愈**：失败时 L3 定位成功 → 把新选择器写回技能（带版本号 + 灰度）

### 5.6 微信 / 小程序 专章（最容易踩坑的地方）

现实约束：

1. **小程序 URL Scheme 不是谁都能生成**
   `weixin://dl/business/?t=xxx` 的 ticket 必须由该小程序的管理员在后台生成，或服务端调 `generatescheme`（要该小程序的 access_token）。**第三方拿不到肯德基的 appid secret，所以无法直接构造**。
2. **微信开放标签**（`wx-open-launch-weapp`）只能在自己认证服务号的微信内置网页里用，且需要 JS-SDK 签名。
3. **所以"进 KFC 小程序"基本只能靠 UI 自动化**：
   - 路径 A：微信首页下拉 → 最近使用的小程序（若用户用过）
   - 路径 B：微信「搜一搜」→ 输入"肯德基"→ 点小程序
   - 路径 C：发现 → 小程序 → 搜索
   - 路径 D：本地存 KFC 小程序码 → 微信扫一扫 → 相册识别（绕但稳）
4. **小程序渲染**：新版 Skyline 渲染下无障碍树可能很差 → 必须准备视觉兜底。
5. **风控**：微信对自动化敏感。**不要**做批量、群发、营销、抢购；单用户低频点单，风险相对可控，但仍需在协议里明示风险。

> 产品上的降风险策略：**优先接"有开放 Deep Link 的目标"**（美团/饿了么/星巴克/瑞幸等自有 App 的 scheme 通常可用），把微信小程序作为"能跑就跑"的可选技能，而不是主链路。

### 5.7 支付与人机接力

- 到"确认订单"页 → 全屏接管提示 → 用户自己输密码/刷脸
- 可选"免密支付"白名单：仅对用户显式授权的单店、单金额上限生效，且每次仍要语音确认
- 绝不代填支付密码、绝不读取验证码自动提交

### 5.8 安全与隐私

| 风险 | 对策 |
|---|---|
| 误操作到银行/聊天 | 包名白名单 + 动作前校验前台包名 |
| 误发消息/误下单 | 写操作二次确认 + 幂等 + 不自动重试 |
| 屏幕内容含隐私 | 默认只上传"裁剪后的控件文本"，不上传整屏；可关闭云 VLM |
| 无障碍被滥用 | 权限说明页 + 运行日志可查 + 一键停止（悬浮球常驻"急停"） |
| 恶意技能包 | 技能市场签名校验 + 权限声明（能操作哪些包） |

---

## 6. KFC 端到端时序（务实版）

| # | 阶段 | 执行者 | 动作 | 校验 |
|---|---|---|---|---|
| 1 | 唤醒 | 用户 | 悬浮球/语音键 → "帮我点个香辣鸡腿堡" | ASR 文本 |
| 2 | 理解 | LLM | 解析：intent=order, brand=KFC, item=香辣鸡腿堡 | 槽位齐全 |
| 3 | 确认 | App | "在肯德基点香辣鸡腿堡，用最近的门店，对吗？" | 用户确认 |
| 4 | 进入 | DeepLink/UI | 拉起微信 → 进 KFC 小程序 | 前台包名 + 页面文本 |
| 5 | 定位 | 系统 | 拿最近门店 / 定位选店 | 门店名出现 |
| 6 | 选品 | 技能 | 搜索/分类 → 点商品 → 规格（默认/上次） | 商品名 + 价格 |
| 7 | 加购 | 技能 | 加入购物车 | 购物车角标变化 |
| 8 | 结算 | 技能 | 去结算 → 确认订单页 | "确认订单"文本 |
| 9 | **交接** | **用户** | 弹窗展示金额 → 用户支付 | 支付结果通知 |
| 10 | 回读 | NotificationListener | 监听支付成功 → 语音播报取餐号 | 订单状态 |

第 4~8 步是 AI 的价值区，第 9 步是红线。

---

## 7. 难点与对策

| 难点 | 影响 | 对策 |
|---|---|---|
| 选择器漂移（App 改版） | 技能失效 | 多级选择器 + L3 自愈 + 技能版本灰度 + 崩溃率监控 |
| 弹窗/广告/升级提示 | 流程卡死 | 全局"干扰处理器"：识别关闭/跳过/以后再说 → 优先点关闭 |
| 页面加载慢 | 误点 | 稳定检测（事件静默 + 目标出现），不用固定 sleep |
| 小程序/Flutter 树稀 | 无法定位 | 截图 + OCR + VLM |
| 验证码/滑块 | 流程中断 | 停下 + 语音提示用户接管 |
| 支付 | 合规红线 | 人机接力 |
| 微信风控 | 封号 | 低频、单用户、不做营销自动化、明示风险 |
| 延迟高 | 体验差 | 技能固化、预热 App、并行读屏、端侧小模型 |
| 成本 | 云 LLM 贵 | 文本树替代截图、L1 命中不调模型、结果缓存 |
| 机型碎片 | 坐标/树差异 | 以语义选择器为主，坐标仅兜底；主流机型回归 |

---

## 8. 合规与分发

- **Google Play**：无障碍权限需专门声明，且"代替用户操作其他 App 完成交易"极易被判违规 → 不建议上架 Play
- **国内商店**：需明示无障碍用途、隐私政策、可关闭；部分商店对"自动化其他 App"审核严格
- **最现实**：官网/Gitee 侧载（和现有 `zongyi-app` 的更新通道一致），用户主动安装
- **Android 13+ 侧载限制**：装完需在"应用信息 → 允许受限设置"后才能开启无障碍，必须在引导页写清楚
- **协议风险**：微信/支付宝/银行类自动化可能违反其用户协议，需在首次启动做风险告知并留痕
- **个人信息保护法**：屏幕内容属于个人信息，默认不上传、可关闭、可清除

---

## 9. 路线图

### P0 · 可行性验证（2~3 周，1 人）
目标：证明"能稳定操作一个真实 App"
- [ ] 无障碍服务骨架：读树、点击、输入、滑动、前台包名
- [ ] 悬浮球 + 语音输入（系统 `SpeechRecognizer` 即可）
- [ ] 3 条硬编码技能：打开微信 / 微信内搜索并进入某小程序 / 某 App 内搜索并播放
- [ ] 成功率埋点 + 录屏回放
- **验收**：单步成功率 > 95%，单条技能 20 次跑通 ≥ 17 次

### P1 · Agent 化（6~8 周，2 人）
- [ ] 树压缩 + LLM 选步（云端 API 起步）
- [ ] 技能 YAML 运行时 + 参数 + 校验点
- [ ] 录制器（人工演示 → 生成技能）
- [ ] 干扰弹窗处理器
- [ ] 10 条真实技能（点单到提交、签到、查快递、播放……）
- **验收**：未录制场景任务成功率 > 70%，技能场景 > 85%

### P2 · 稳定性与体验（8~12 周）
- [ ] 截图 + 端侧 OCR 兜底
- [ ] VLM 兜底 + 失败自愈回写
- [ ] 支付前交接 UI + 通知回读
- [ ] 端侧小模型（意图解析）降延迟降成本
- [ ] 机型兼容矩阵 + 灰度发布
- **验收**：技能场景 > 92%，平均任务耗时 < 15s，人工接管率 < 20%

### P3 · 平台化
- [ ] 技能市场（签名 + 权限声明）
- [ ] 可视化技能编辑器
- [ ] 多步跨 App 任务（"点完汉堡顺手把取餐码发给我"）
- [ ] 开放 API / 给其他语音助手调用

---

## 10. 工程结构（建议独立 APK，可复用现有 Gradle 配置）

```
app-agent/
├── app/                          # 壳 + UI
├── core-agent/                   # 规划、任务状态机、降级策略
├── core-perception/              # 无障碍树压缩、OCR、VLM 客户端
├── core-action/                  # 原子动作、等待/校验/重试
├── core-skill/                   # DSL 解析、运行时、录制器
├── service-accessibility/        # AccessibilityService
├── service-assist/               # VoiceInteractionService（可选）
└── skills/                       # 内置 YAML 技能（assets）
```

关键类草图：

```kotlin
class AgentAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(e: AccessibilityEvent) { ScreenBus.emit(e) }
    override fun onInterrupt() {}

    fun snapshot(): UiDom = DomCompressor.compress(rootInActiveWindow)

    fun tap(node: AccessibilityNodeInfo): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
        node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true

    fun tap(x: Float, y: Float) = dispatchGesture(
        GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, 60)
        ).build(), null, null
    )
}
```

```kotlin
// 一级降级：技能 → LLM → VLM
suspend fun nextAction(state: TaskState, dom: UiDom): Action =
    skillRuntime.match(state)?.let { it.action }
        ?: llmPlanner.decide(state, dom.compact())      // 文本，便宜
        ?: vlmGrounder.locate(state, screenshot())       // 图片，兜底
```

技能运行时伪代码：

```kotlin
for (step in skill.steps) {
    val ok = withTimeout(step.timeout) { executor.run(step, ctx) }
    if (!ok) {
        if (step.risk == Risk.WRITE) failFast(step)     // 写操作不重试
        else if (!healer.recover(step)) fail(step)      // 自愈一次
    }
}
```

---

## 11. 验收指标

| 指标 | P0 | P1 | P2 |
|---|---|---|---|
| 单步成功率 | 95% | 97% | 98%+ |
| 技能任务成功率 | 85% | 85% | 92%+ |
| 未录制任务成功率 | — | 70% | 80% |
| 平均任务耗时 | — | 25s | 15s |
| 人工接管率 | — | 30% | 20% |
| 技能失效率/月 | — | 20% | 8% |

---

## 12. 需要拍板的 4 件事

1. **独立 App 还是并入综艺盒子？** 建议独立 APK（权限模型、审核、更新节奏都不同），只复用构建脚本。
2. **是否接受 Shizuku？** 接受 → 稳定性 +30%，但安装门槛高；不接受 → 纯无障碍也能做。
3. **首发场景选哪个？** 建议先做"低风险高频"：视频 App 搜索播放、每日签到、查快递、发固定消息；KFC 点单作为 P1 的旗舰 Demo（到提交订单为止）。
4. **模型放哪？** 云 API 起步（快），P2 再上端侧小模型做意图解析 + 端侧 OCR，降低延迟与隐私风险。

---

## 附：一句话总结

> 这不是"插件"，而是一个**运行在无障碍/ADB 之上的 GUI Agent**：
> 用 Deep Link 抄近路，用无障碍树做语义，用截图 VLM 兜底，用技能 DSL 固化经验，用人工接力守住支付红线。
> 技术上今天就能做出来，难的是稳定性工程和合规边界。
