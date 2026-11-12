package com.zongyi.box.data

/**
 * 版本更新配置 —— 零服务端方案（Gitee 分发仓库）。
 *
 * 仓库里只放一个几百字节的 update.json，APK 放在该仓库的「发行版」附件里：
 * ```
 * update.json        版本清单，App 只读这一个（raw 可匿名读）
 * app-release.apk    发行版附件（仓库 raw 对大文件要求登录，附件通道不受限制）
 * ```
 * 客户端请求的固定地址是 Gitee raw 路径：
 *
 *     https://gitee.com/{REPO}/raw/{BRANCH}/update.json
 *
 * 每次发版覆盖提交 update.json、发一个新发行版，地址保持不变 ——
 * 不需要服务器、不需要域名。
 *
 * 注意：
 * 1. 仓库必须是**公开**的，私有仓库的 raw 与附件都需要登录态，App 取不到；
 * 2. Gitee raw 走 CDN 有缓存，拉清单时 UpdateChecker 会自动追加时间戳参数破缓存；
 * 3. update.json 里的 apk 写发行版附件的绝对地址（发布脚本自动填）。
 *
 * 发布流程见 docs/update.md，一键发布用 tools/publish_update.sh。
 */
object UpdateConfig {

    /** Gitee 分发仓库：用户名/仓库名（保持 USER/ 开头视为未配置，App 静默跳过检查） */
    private const val REPO = "liaz-app/zongyi-app"

    /** 文件所在分支，与仓库默认分支一致 */
    private const val BRANCH = "master"

    /** 启动时自动检查更新 */
    const val AUTO_CHECK_ON_START = true

    /** 自动检查节流：距上次检查不足该间隔则跳过（手动检查不受限） */
    const val CHECK_THROTTLE_MS = 10 * 60 * 1000L

    /** 清单地址，按顺序探测，第一个能取到的生效 */
    val manifestUrls: List<String> by lazy {
        if (!isConfigured) emptyList()
        else listOf(
            // Gitee 官方 raw 短链（部分网络/风控策略下会 404）
            "https://gitee.com/$REPO/raw/$BRANCH/update.json",
            // 实测可匿名访问的回源域名，作为兜底
            "https://raw.giteeusercontent.com/$REPO/raw/$BRANCH/update.json",
        )
    }

    /** 是否已填入真实仓库 */
    val isConfigured: Boolean get() = REPO.isNotBlank() && !REPO.startsWith("USER/")
}
