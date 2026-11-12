# 应用内版本更新（零服务端）

不部署服务器、不买域名、不接数据库：把 `update.json` 和 `app-release.apk` 作为**同一个 GitHub Release 的两个附件**发布，
客户端永远只请求一个固定地址：

```
https://github.com/<你的仓库>/releases/latest/download/update.json
```

这个地址始终指向「最新的那个 Release」，所以发一次新版就换一次内容，中间不需要任何服务端逻辑。

## 一、一次性配置（5 分钟）

1. 新建一个**公开**仓库，例如 `zhangsan/zongyibox-release`（只放 Release，不放源码也可以）。
2. 把这个仓库填到 `app/src/main/java/com/zongyi/box/data/UpdateConfig.kt`：

   ```kotlin
   private const val REPO = "zhangsan/zongyibox-release"
   ```

   国内网络不稳可以再填一个 Gitee 镜像仓库名（可选）：

   ```kotlin
   private const val GITEE_REPO = "zhangsan/zongyibox-release"
   ```

   留占位值 `USER/...` 时 App 视为「未配置」，不发任何请求、不打扰用户。

3. 重新打包安装一次。**这一步是必须的**：只有新版本才带更新代码，老版本无法自我唤醒。

## 二、每次发新版

```bash
# 1. 递增 app/build.gradle.kts 的 versionCode（必须 +1）与 versionName
#    versionCode = 2
#    versionName = "1.0.1"

# 2. 打包
./tools/gradle-8.14.2/bin/gradle assembleRelease

# 3. 生成 update.json（会自动算体积和 sha256，并检查线上 versionCode 是否已追上）
tools/publish_update.sh --changelog "· 修复播放器偶发黑屏
· 新增倍速记忆"

# 4. 自动发 Release（需 GitHub CLI：gh auth login）
tools/publish_update.sh --upload --changelog "..."
```

没有 `gh` 就手动做第 4 步：在仓库里新建 Release（tag 例如 `v1.0.1`），把 `build/publish/` 下的
`app-release.apk` 与 `update.json` **按原名**上传为附件。附件名不要改，否则固定下载地址会失效。

上传完成后，已安装用户下次启动 App 就会收到提示，无需等待任何缓存刷新。

## 三、客户端行为

| 场景 | 行为 |
|---|---|
| 冷启动 | 延迟 2.5s（避开开屏广告）静默检查，10 分钟内不重复请求 |
| 有新版本 | 弹窗：版本号、包体积、更新内容、`以后再说 / 立即更新` |
| 同一版本点过「以后再说」 | 不再自动弹；换新版本会重新提示；「我的 → 检查更新」可手动唤出 |
| 强制更新 | 清单里 `forceBelow` 大于本地 `versionCode` 时，弹窗不可取消、只能更新或退出 |
| 下载 | 多镜像重试（GitHub 直链 → ghproxy → gh-proxy），显示百分比进度，支持弹窗关闭即取消 |
| 校验 | 比对 `size` 与 `sha256`，不匹配自动换镜像重下 |
| 安装 | 写入 `cacheDir/update/` → FileProvider 授权 → 拉起系统安装器；未授权「安装未知应用」时引导到设置页 |

## 四、update.json 字段

```json
{
  "versionCode": 2,
  "versionName": "1.0.1",
  "apk": "app-release.apk",
  "size": 18342016,
  "sha256": "8f2c…",
  "forceBelow": 0,
  "publishedAt": "2026-11-12",
  "changelog": "· 修复播放器偶发黑屏\n· 新增倍速记忆"
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `versionCode` | 是 | 与本地 `BuildConfig.VERSION_CODE` 比较，**唯一判断依据** |
| `versionName` | 否 | 仅用于展示，缺失时用 versionCode 代替 |
| `apk` | 是 | 可写相对路径（相对清单所在目录）或完整 http(s) 地址 |
| `apkUrls` | 否 | 字符串数组，显式指定多个下载地址，与 `apk` 合并去重 |
| `size` | 否 | 字节数，用于进度分母与完整性校验 |
| `sha256` | 否 | 小写十六进制，下载后校验，失败自动换镜像 |
| `forceBelow` | 否 | 大于 0 时，本地 versionCode 低于它的用户被强制更新 |
| `changelog` | 否 | 纯文本，用 `\n` 换行，弹窗内最多显示 10 行可滚动 |
| `publishedAt` | 否 | 发布日期，仅记录用途 |

## 五、必须注意的坑

- **versionCode 一定要递增**。相同 versionCode 客户端不会提示，这是最常见的「传了包没反应」原因；
  `publish_update.sh` 会主动比对线上版本并给出警告。
- **签名必须一致**。升级安装要求新旧 APK 使用同一个签名（本项目固定用 `zongyibox.jks`），
  换了签名只能卸载重装，用户会丢历史/收藏。
- **仓库必须公开**。私有仓库的 Release 附件需要 token，客户端拿不到。
- **不要删旧 Release**。`releases/latest` 取的是最新一个，删了会导致回退到更旧版本。
- 国内直连 GitHub 偶尔慢：清单请求有 12s 总超时，失败会自动降级到 ghproxy / Gitee 镜像，
  全失败则静默跳过，不影响 App 正常使用。

## 六、换成其它托管

不想用 GitHub 的话，原理完全一样：任何能公开访问 `update.json` 的地方都行
（对象存储 + 公开读、Cloudflare R2/Pages、自己的静态空间、Gitee raw…），
只要把地址加到 `UpdateConfig.manifestUrls` 即可，`apk` 字段写同目录相对路径或绝对地址。
注意 CDN 缓存：清单类文件不要设长缓存，否则发版后要等缓存过期才生效——这也是默认用
`releases/latest/download` 的原因（GitHub 该地址不快缓存）。
