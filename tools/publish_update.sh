#!/usr/bin/env bash
# ============================================================================
# 一键发布应用更新（零服务端）
#
# 作用：读取 app/build.gradle.kts 里的 versionCode/versionName，为 release APK
#      生成配套的 update.json（含体积 + sha256），可选直接创建一个 GitHub Release
#      并把 app-release.apk / update.json 作为附件上传。
#
# 用法：
#   tools/publish_update.sh                          # 只生成 build/publish/update.json
#   tools/publish_update.sh --changelog "· 修好了X"   # 指定更新说明
#   tools/publish_update.sh --upload                  # 生成并直接用 gh 发 Release
#   tools/publish_update.sh --apk path/to/other.apk   # 指定别的安装包
#
# 前提：已执行 ./tools/gradle-8.14.2/bin/gradle assembleRelease
#      （--upload 额外需要安装 GitHub CLI 并 gh auth login）
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE_FILE="$ROOT/app/build.gradle.kts"
CONFIG_FILE="$ROOT/app/src/main/java/com/zongyi/box/data/UpdateConfig.kt"
OUT_DIR="$ROOT/build/publish"
APK_DEFAULT="$ROOT/app/build/outputs/apk/release/app-release.apk"

APK=""
CHANGELOG=""
CHANGELOG_FILE=""
UPLOAD="0"
TAG=""

usage() {
  sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
  case "$1" in
    --apk) APK="${2:-}"; shift 2 ;;
    --changelog) CHANGELOG="${2:-}"; shift 2 ;;
    --changelog-file) CHANGELOG_FILE="${2:-}"; shift 2 ;;
    --tag) TAG="${2:-}"; shift 2 ;;
    --upload) UPLOAD="1"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数: $1（--help 查看用法）"; exit 1 ;;
  esac
done

# ---------- 版本号 ----------
VERSION_CODE=$(sed -n 's/.*versionCode[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$GRADLE_FILE" | head -1)
VERSION_NAME=$(sed -n 's/.*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$GRADLE_FILE" | head -1)
REPO=$(sed -n 's/.*const val REPO = "\([^"]*\)".*/\1/p' "$CONFIG_FILE" | head -1)

if [ -z "$VERSION_CODE" ] || [ -z "$VERSION_NAME" ]; then
  echo "无法从 $GRADLE_FILE 解析 versionCode/versionName" >&2
  exit 1
fi

# ---------- 安装包 ----------
[ -n "$APK" ] || APK="$APK_DEFAULT"
if [ ! -f "$APK" ]; then
  echo "找不到安装包: $APK" >&2
  echo "请先执行: ./tools/gradle-8.14.2/bin/gradle assembleRelease" >&2
  exit 1
fi

SIZE=$(wc -c < "$APK" | tr -d ' \r')
if command -v sha256sum >/dev/null 2>&1; then
  SHA=$(sha256sum "$APK" | cut -d' ' -f1)
elif command -v shasum >/dev/null 2>&1; then
  SHA=$(shasum -a 256 "$APK" | cut -d' ' -f1)
else
  SHA=""
  echo "警告：未找到 sha256sum/shasum，清单将不含校验值" >&2
fi

# ---------- 更新说明 ----------
if [ -n "$CHANGELOG_FILE" ]; then
  [ -f "$CHANGELOG_FILE" ] || { echo "找不到更新说明文件: $CHANGELOG_FILE" >&2; exit 1; }
  CHANGELOG=$(cat "$CHANGELOG_FILE")
elif [ -z "$CHANGELOG" ] && [ -f "$ROOT/docs/changelog/$VERSION_NAME.txt" ]; then
  CHANGELOG=$(cat "$ROOT/docs/changelog/$VERSION_NAME.txt")
fi
[ -n "$CHANGELOG" ] || CHANGELOG="· 优化播放体验
· 修复已知问题"

json_escape() {
  awk 'BEGIN{ORS=""} {gsub(/\\/,"\\\\"); gsub(/"/,"\\\""); if (NR>1) printf "\\n"; printf "%s", $0}'
}
CHANGELOG_JSON=$(printf '%s' "$CHANGELOG" | json_escape)

# ---------- 生成 update.json ----------
mkdir -p "$OUT_DIR"
PUBLISHED_AT=$(date +%Y-%m-%d)

cat > "$OUT_DIR/update.json" <<EOF
{
  "versionCode": $VERSION_CODE,
  "versionName": "$VERSION_NAME",
  "apk": "app-release.apk",
  "size": $SIZE,
  "sha256": "$SHA",
  "forceBelow": 0,
  "publishedAt": "$PUBLISHED_AT",
  "changelog": "$CHANGELOG_JSON"
}
EOF

# 统一附件名，保证 releases/latest/download/app-release.apk 这个固定地址可用
cp -f "$APK" "$OUT_DIR/app-release.apk"

echo ""
echo "已生成:"
echo "  $OUT_DIR/update.json"
echo "  $OUT_DIR/app-release.apk  ($(awk -v s="$SIZE" 'BEGIN{printf "%.1f MB", s/1048576}'))"
echo "  versionCode=$VERSION_CODE  versionName=$VERSION_NAME  sha256=${SHA:0:16}…"
echo ""

# ---------- 线上版本比对（防止忘记升 versionCode）----------
if [ -n "$REPO" ] && ! echo "$REPO" | grep -q '^USER/'; then
  URL="https://github.com/$REPO/releases/latest/download/update.json"
  if command -v curl >/dev/null 2>&1; then
    REMOTE=$(curl -fsSL --max-time 10 "$URL" 2>/dev/null | sed -n 's/.*"versionCode"[[:space:]]*:[[:space:]]*\([0-9]*\).*/\1/p' | head -1 || true)
    if [ -n "$REMOTE" ]; then
      if [ "$VERSION_CODE" -le "$REMOTE" ]; then
        echo "⚠ 线上已是 versionCode=$REMOTE，本地 $VERSION_CODE 不更大 —— 客户端不会提示更新！"
        echo "  请先改 app/build.gradle.kts 的 versionCode（+1）与 versionName 再重新打包。"
        echo ""
      else
        echo "线上 versionCode=$REMOTE → 本次 $VERSION_CODE，可正常推送更新。"
      fi
    fi
  fi
fi

# ---------- 发布 ----------
if [ "$UPLOAD" = "1" ]; then
  command -v gh >/dev/null 2>&1 || { echo "未安装 GitHub CLI（gh），无法自动发布" >&2; exit 1; }
  [ -n "$REPO" ] && ! echo "$REPO" | grep -q '^USER/' || { echo "UpdateConfig.REPO 未配置" >&2; exit 1; }
  [ -n "$TAG" ] || TAG="v$VERSION_NAME"
  echo "创建 Release $TAG 并上传附件到 $REPO …"
  if gh release view "$TAG" -R "$REPO" >/dev/null 2>&1; then
    gh release upload "$TAG" "$OUT_DIR/app-release.apk" "$OUT_DIR/update.json" -R "$REPO" --clobber
  else
    gh release create "$TAG" "$OUT_DIR/app-release.apk" "$OUT_DIR/update.json" \
      -R "$REPO" --title "$TAG" --notes "$CHANGELOG"
  fi
  echo "已发布: https://github.com/$REPO/releases/tag/$TAG"
  echo "客户端检查地址: https://github.com/$REPO/releases/latest/download/update.json"
else
  echo "下一步（任选其一）："
  echo "  A. 自动发布：装好 gh 并 gh auth login，然后 tools/publish_update.sh --upload"
  echo "  B. 手动发布：在 GitHub 仓库 $REPO 新建 Release（tag 用 v$VERSION_NAME），"
  echo "     把 $OUT_DIR 下的 app-release.apk 与 update.json 作为附件上传（文件名不要改）。"
  echo "  C. 其它托管：把这两个文件放到任意可公开访问的目录，"
  echo "     并把地址填到 UpdateConfig.kt 的 manifestUrls。"
fi
