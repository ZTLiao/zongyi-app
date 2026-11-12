# 应用内版本更新（Gitee 分发 · 零服务端）

不部署服务器、不买域名、不接数据库。用一个 Gitee 公开仓库当「版本接口」：

| 文件 | 放哪 | 作用 | 匿名可访问? |
|---|---|---|---|
| `update.json` | 仓库 `master` 根目录 | 版本清单，App 只读这一个 | ✅ 可以（几百字节） |
| `app-release.apk` | 该仓库的**发行版附件** | 安装包本体 | ✅ 可以（公开 CDN） |

App 请求的固定地址：

```
https://gitee.com/liaz-app/zongyi-app/raw/master/update.json
```

每次发版覆盖提交 `update.json`、发一个新的发行版，地址永远不变。

## 为什么 APK 不能和 json 一起放仓库

Gitee 对仓库里的 raw 文件下载**限制大文件**，实测 15MB 的 APK 直接返回：

```
403 Forbidden — large file require login for access.
```

而**发行版附件**走的是公开 CDN（`foruda.gitee.com/attach_file/...`），匿名可下载，
所以 APK 必须走发行版，`update.json` 里的 `apk` 字段直接写附件地址。

> 另一个坑：`https://gitee.com/<repo>/raw/<branch>/update.json` 这个短链在部分网络
> 会被 Gitee 风控成 `404 Repository or file not found`，而回源域名
> `https://raw.giteeusercontent.com/<repo>/raw/<branch>/update.json` 正常返回 200。
> App 里两个地址都配了，按顺序探测、失败自动降级。

## 一、一次性配置

1. Gitee 建一个**公开**仓库（当前用 `liaz-app/zongyi-app`），私有仓库的 raw 需要登录态，App 取不到。
2. 生成私人令牌：https://gitee.com/profile/personal_access_tokens → 勾 **projects**。
   用令牌而不是登录密码，可随时吊销、可限权。
3. 确认 `app/src/main/java/com/zongyi/box/data/UpdateConfig.kt` 里的仓库与分支：

   ```kotlin
   private const val REPO = "liaz-app/zongyi-app"
   private const val BRANCH = "master"
   ```

## 二、每次发新版

```bash
# 1. versionCode +1，versionName 改好（必须，否则客户端不会提示）
#    versionCode = 3
#    versionName = "1.0.2"

# 2. 打包
./tools/gradle-8.14.2/bin/gradle assembleRelease

# 3. 一键发布：建发行版 → 传 APK 附件 → 更新并推送 update.json
GITEE_TOKEN=你的令牌 tools/publish_update.sh --upload \
  --changelog "· 修复xxx
· 优化yyy"
```

脚本会做这些事：

1. 比对线上 `update.json` 的 `versionCode`，**如果没递增直接报错退出**（防止发了包客户端不提示）
2. 用 API 创建发行版 `v{versionName}`，把 APK 传成附件 `app-release.apk`
3. 从返回值取附件地址，写进 `update.json` 的 `apk` 字段
4. 把 `update.json` 推到仓库根目录（顺带把仓库里遗留的 apk 删掉）

只生成产物不上传（本地检查用）：

```bash
tools/publish_update.sh --changelog "..."   # 产物在 build/publish/
```

## 三、客户端行为

| 场景 | 行为 |
|---|---|
| 冷启动 | 延迟 2.5s（避开开屏广告）静默检查，10 分钟内不重复请求 |
| 有新版本 | 弹窗：版本号、包体积、更新内容、`以后再说 / 立即更新` |
| 同版本点过「以后再说」 | 不再自动弹；换新版本会重新提示；「我的 → 检查更新」可手动唤出 |
| 强制更新 | 清单 `forceBelow` 大于本地 `versionCode` 时，弹窗不可取消，只能更新或退出 |
| 下载 | 显示百分比进度；弹窗关闭即取消；失败自动换下一个地址重试 |
| 校验 | 比对 `size` 与 `sha256`，不一致视为下载损坏，换镜像重下 |
| 安装 | 写入 `cacheDir/update/` → FileProvider 授权 → 拉起系统安装器；未授权「安装未知应用」时引导到设置页 |

## 四、update.json 字段

```json
{
  "versionCode": 2,
  "versionName": "1.0.1",
  "apk": "https://gitee.com/liaz-app/zongyi-app/releases/download/v1.0.1/app-release.apk",
  "size": 16333928,
  "sha256": "9a2ba48e…",
  "forceBelow": 0,
  "publishedAt": "2026-11-12",
  "changelog": "· 新增应用内检查更新\n· 修复部分机型播放器黑屏"
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `versionCode` | 是 | 与本地 `BuildConfig.VERSION_CODE` 比较，**唯一判断依据** |
| `versionName` | 否 | 仅展示用，缺失时用 versionCode 代替 |
| `apk` | 是 | 完整 http(s) 地址，或相对路径（相对清单所在目录，会解析成同目录文件） |
| `apkUrls` | 否 | 字符串数组，额外下载地址，与 `apk` 合并去重 |
| `size` | 否 | 字节数，进度分母 + 完整性校验 |
| `sha256` | 否 | 小写十六进制，下载后校验，失败自动换地址 |
| `forceBelow` | 否 | 大于 0 时，本地 versionCode 低于它的用户被强制更新 |
| `changelog` | 否 | 纯文本，`\n` 换行，弹窗内最多显示 10 行可滚动 |
| `publishedAt` | 否 | 发布日期，仅记录 |

## 五、必须注意的坑

- **versionCode 一定要递增**。相同版本号客户端不提示，这是「传了包没反应」的头号原因；
  发布脚本会主动比对并拦截。
- **签名必须一致**。升级安装要求新旧 APK 同签名（本项目固定 `zongyibox.jks`），
  换签名只能卸载重装，用户会丢历史/收藏。该 jks 已加入 `.gitignore`，不入库。
- **仓库必须公开**。私有仓库的 raw 与附件都需要登录。
- **不要删旧发行版**。用户手里的旧包可能正在下载旧 tag 的附件。
- **更新清单缓存**：`update.json` 走 CDN 有缓存，`UpdateChecker` 拉取时会自动追加
  `?_t=时间戳` 破缓存，失败再退回不带参数的地址。
- **Gitee 内容审核**：公开仓库的 APK 可能被巡检，影视类分发包有被举报下架的风险，
  必要时换对象存储（同样只是改 `UpdateConfig` 里的地址）。

## 六、换成其它托管

原理不变：任何能公开访问 `update.json` 的地方都行（对象存储公开读、Cloudflare R2/Pages、
自己的静态空间）。把地址加到 `UpdateConfig.manifestUrls`，并把 `apk` 写成可公开下载的绝对地址即可。
注意**清单不要设长缓存**，否则发版后要等缓存过期才生效。
