package com.zongyi.box.data

/**
 * 版本更新配置 —— 零服务端方案。
 *
 * 原理：APK 与 update.json 作为同一个 GitHub Release 的附件发布，
 * App 只请求一个永远指向「最新 Release」的固定地址：
 *
 *     https://github.com/{REPO}/releases/latest/download/update.json
 *
 * 发布新版本时只需重新发一个 Release，该地址内容立即变化，
 * 不需要服务器、不需要数据库、不需要域名（GitHub 免费额度足够）。
 * 可选的 raw / Gitee / ghproxy 只是同一份清单的镜像，用于国内网络兜底。
 *
 * 唯一需要改的地方：[REPO] 改成你自己的仓库（形如 "zhangsan/zongyibox-release"）。
 * 仓库建好后放一个 main 分支（可以有 README），Release 里上传
 * app-release.apk 与 update.json 两个附件即可。
 *
 * 发布流程见 docs/update.md，一键发布用 tools/publish_update.sh。
 */
object UpdateConfig {

    /** GitHub 发布仓库：用户名/仓库名。保持默认占位值则视为未配置，App 静默跳过检查 */
    private const val REPO = "USER/zongyibox-release"

    /** Gitee 镜像仓库（可选）：用户名/仓库名，留空不启用 */
    private const val GITEE_REPO = ""

    /** 启动时自动检查更新 */
    const val AUTO_CHECK_ON_START = true

    /** 自动检查节流：距上次检查不足该间隔则跳过（手动检查不受限） */
    const val CHECK_THROTTLE_MS = 10 * 60 * 1000L

    /** 清单候选地址，按顺序探测，第一个能取到的生效 */
    val manifestUrls: List<String> by lazy {
        buildList {
            if (isConfigured) {
                // 指向 latest Release，无需 CDN 刷新，发版即生效
                add("https://github.com/$REPO/releases/latest/download/update.json")
                // 国内加速镜像（同时会用于 APK 下载）
                add("https://ghproxy.net/https://github.com/$REPO/releases/latest/download/update.json")
                // 备用：直接把 update.json 提交到仓库根目录
                add("https://raw.githubusercontent.com/$REPO/main/update.json")
            }
            if (GITEE_REPO.isNotBlank()) {
                add("https://gitee.com/$GITEE_REPO/raw/main/update.json")
            }
        }
    }

    /** 是否已填入真实仓库；未配置时不做任何网络请求，也不打扰用户 */
    val isConfigured: Boolean get() = REPO.isNotBlank() && !REPO.startsWith("USER/")
}
