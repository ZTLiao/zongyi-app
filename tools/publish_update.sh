#!/usr/bin/env bash
# ============================================================================
# 一键发布应用更新（Gitee 分发仓库 · 零服务端）
#
# 做的事情：
#   1. 读 app/build.gradle.kts 的 versionCode/versionName
#   2. 算 APK 的体积与 sha256
#   3. 在 Gitee 建发行版（tag = v版本号），把 APK 传成发行版附件
#      —— 只有发行版附件能匿名下载大文件，仓库 raw 通道 >1MB 会要求登录
#   4. 生成 update.json（apk 字段指向附件地址），推到分发仓库根目录
#
# 用法：
#   tools/publish_update.sh                            # 只生成 build/publish/update.json
#   GITEE_TOKEN=xxx tools/publish_update.sh --upload   # 生成 + 建发行版 + 传 APK + 推清单
#   tools/publish_update.sh --changelog "· 修好了X"
#   tools/publish_update.sh --apk path/to/other.apk
#
# 前提：已执行 ./tools/gradle-8.14.2/bin/gradle assembleRelease
#      --upload 需要 Gitee 私人令牌（设置 → 私人令牌，勾 projects），
#      不要用登录密码；令牌可随时吊销。
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE_FILE="$ROOT/app/build.gradle.kts"
CONFIG_FILE="$ROOT/app/src/main/java/com/zongyi/box/data/UpdateConfig.kt"
OUT_DIR="$ROOT/build/publish"
APK_DEFAULT="$ROOT/app/build/outputs/apk/release/app-release.apk"
API="https://gitee.com/api/v5"

APK=""
CHANGELOG=""
CHANGELOG_FILE=""
UPLOAD="0"

usage() { sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
  case "$1" in
    --apk) APK="${2:-}"; shift 2 ;;
    --changelog) CHANGELOG="${2:-}"; shift 2 ;;
    --changelog-file) CHANGELOG_FILE="${2:-}"; shift 2 ;;
    --upload) UPLOAD="1"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数: $1（--help 查看用法）"; exit 1 ;;
  esac
done

# ---------- 版本号 ----------
VERSION_CODE=$(sed -n 's/.*versionCode[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$GRADLE_FILE" | head -1)
VERSION_NAME=$(sed -n 's/.*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$GRADLE_FILE" | head -1)
REPO=$(sed -n 's/.*const val REPO = "\([^"]*\)".*/\1/p' "$CONFIG_FILE" | head -1)
BRANCH=$(sed -n 's/.*const val BRANCH = "\([^"]*\)".*/\1/p' "$CONFIG_FILE" | head -1)
[ -n "$BRANCH" ] || BRANCH="master"
TAG="v$VERSION_NAME"

if [ -z "$VERSION_CODE" ] || [ -z "$VERSION_NAME" ]; then
  echo "无法从 $GRADLE_FILE 解析 versionCode/versionName" >&2; exit 1
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
  SHA=""; echo "警告：未找到 sha256sum/shasum，清单将不含校验值" >&2
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

mkdir -p "$OUT_DIR" "$ROOT/build/tmp"
TMP_JSON="$ROOT/build/tmp"
API_URL="$API/repos/$REPO"

# ---------- 发布：建发行版 + 传 APK ----------
APK_URL="https://gitee.com/$REPO/releases/download/$TAG/app-release.apk"

if [ "$UPLOAD" = "1" ]; then
  : "${GITEE_TOKEN:?请设置 GITEE_TOKEN（Gitee 私人令牌，非登录密码）}"
  [ -n "$REPO" ] && ! echo "$REPO" | grep -q '^USER/' || { echo "UpdateConfig.REPO 未配置" >&2; exit 1; }

  echo "① 检查线上版本…"
  ONLINE_CODE=$(curl -fsSL --max-time 15 "$API_URL/contents/update.json?access_token=$GITEE_TOKEN" 2>/dev/null \
    | grep -o '"versionCode"[^,]*' | head -1 | grep -o '[0-9]*' || true)
  if [ -n "${ONLINE_CODE:-}" ] && [ "$VERSION_CODE" -le "$ONLINE_CODE" ]; then
    echo "⚠ 线上清单已是 versionCode=$ONLINE_CODE，本次 $VERSION_CODE 不更大，客户端不会提示更新。" >&2
    echo "  请先改 app/build.gradle.kts 的 versionCode（+1）与 versionName 再重新打包。" >&2
    exit 1
  fi

  echo "② 创建发行版 $TAG …"
  REL_JSON="$TMP_JSON/release.json"
  HTTP=$(curl -s -o "$REL_JSON" -w '%{http_code}' --max-time 40 -X POST "$API_URL/releases" \
    --data-urlencode "access_token=$GITEE_TOKEN" \
    --data-urlencode "tag_name=$TAG" \
    --data-urlencode "name=综艺盒子 $TAG" \
    --data-urlencode "target_commitish=$BRANCH" \
    --data-urlencode "body=$CHANGELOG")

  RELEASE_ID=$(sed -n 's/.*"id":[[:space:]]*\([0-9]*\).*/\1/p' "$REL_JSON" | head -1)
  if [ "$HTTP" != "201" ]; then
    # tag 已存在时 API 报错，查出来复用
    curl -s --max-time 20 "$API_URL/releases/tags/$TAG?access_token=$GITEE_TOKEN" -o "$REL_JSON"
    RELEASE_ID=$(sed -n 's/.*"id":[[:space:]]*\([0-9]*\).*/\1/p' "$REL_JSON" | head -1)
    if [ -z "$RELEASE_ID" ]; then
      echo "创建发行版失败（HTTP $HTTP）：$(head -c 200 "$REL_JSON")" >&2
      exit 1
    fi
    echo "   发行版已存在，复用 id=$RELEASE_ID（要重发请先升版本号）"
  fi

  echo "③ 上传 APK 附件（$(awk -v s="$SIZE" 'BEGIN{printf "%.1f MB", s/1048576}')）…"
  ATTACH_JSON="$TMP_JSON/attach.json"
  HTTP=$(curl -s -o "$ATTACH_JSON" -w '%{http_code}' --max-time 600 -X POST \
    "$API_URL/releases/$RELEASE_ID/attach_files" \
    -F "access_token=$GITEE_TOKEN" \
    -F "file=@$APK;filename=app-release.apk;type=application/vnd.android.package-archive")

  if [ "$HTTP" != "201" ]; then
    # 同名附件已存在：删掉旧附件再传
    echo "   同名附件已存在，删除后重传…"
    REL_DETAIL="$TMP_JSON/rel_detail.json"
    curl -s --max-time 30 "$API_URL/releases/$RELEASE_ID?access_token=$GITEE_TOKEN" -o "$REL_DETAIL"
    for AID in $(grep -o '"id":[[:space:]]*[0-9]*' "$REL_DETAIL" | grep -o '[0-9]*'); do
      curl -s -o /dev/null -X DELETE \
        "$API_URL/releases/$RELEASE_ID/attach_files/$AID?access_token=$GITEE_TOKEN" || true
    done
    HTTP=$(curl -s -o "$ATTACH_JSON" -w '%{http_code}' --max-time 600 -X POST \
      "$API_URL/releases/$RELEASE_ID/attach_files" \
      -F "access_token=$GITEE_TOKEN" \
      -F "file=@$APK;filename=app-release.apk;type=application/vnd.android.package-archive")
    if [ "$HTTP" != "201" ]; then
      echo "上传 APK 失败（HTTP $HTTP）：$(head -c 200 "$ATTACH_JSON")" >&2
      exit 1
    fi
  fi

  GOT=$(sed -n 's/.*"browser_download_url":[[:space:]]*"\([^"]*\)".*/\1/p' "$ATTACH_JSON" | head -1)
  [ -n "$GOT" ] && APK_URL="$GOT"
  echo "   附件地址: $APK_URL"
fi

# ---------- 生成 update.json ----------
PUBLISHED_AT=$(date +%Y-%m-%d)
cat > "$OUT_DIR/update.json" <<EOF
{
  "versionCode": $VERSION_CODE,
  "versionName": "$VERSION_NAME",
  "apk": "$APK_URL",
  "size": $SIZE,
  "sha256": "$SHA",
  "forceBelow": 0,
  "publishedAt": "$PUBLISHED_AT",
  "changelog": "$CHANGELOG_JSON"
}
EOF
cp -f "$APK" "$OUT_DIR/app-release.apk"

echo ""
echo "清单: $OUT_DIR/update.json"
echo "  versionCode=$VERSION_CODE  versionName=$VERSION_NAME  $((SIZE/1048576))MB"

# ---------- 推送清单到分发仓库 ----------
if [ "$UPLOAD" = "1" ]; then
  echo "④ 推送 update.json 到 $REPO ($BRANCH)…"
  WORK="$ROOT/build/tmp/work"
  rm -rf "$WORK"
  ENC_USER=$(printf '%s' "${REPO%%/*}" | sed 's/@/%40/g')
  PUSH_URL="https://$ENC_USER:$GITEE_TOKEN@gitee.com/$REPO.git"

  if git clone --depth 1 -b "$BRANCH" "https://gitee.com/$REPO.git" "$WORK" >/dev/null 2>&1; then
    :
  else
    git init -q -b "$BRANCH" "$WORK"
    git -C "$WORK" remote add origin "https://gitee.com/$REPO.git"
  fi

  # 分发仓库只保留 update.json；APK 走发行版附件，不再占仓库空间
  cp -f "$OUT_DIR/update.json" "$WORK/update.json"
  (
    cd "$WORK"
    git rm -q --cached --ignore-unmatch app-release.apk 2>/dev/null || true
    rm -f app-release.apk
    git add -f update.json
    if git diff --cached --quiet; then
      echo "   清单无变化，跳过提交"
    else
      git -c user.name="publish_update" -c user.email="publish@local" \
        commit -q -m "release: $TAG (versionCode $VERSION_CODE)"
      git -c credential.helper= push "$PUSH_URL" "HEAD:$BRANCH"
      echo "   已推送"
    fi
  )
  rm -rf "$WORK"
  echo ""
  echo "完成"
  echo "  清单地址: https://gitee.com/$REPO/raw/$BRANCH/update.json"
  echo "  安装包:   $APK_URL"
  echo "  发行版页: https://gitee.com/$REPO/releases/tag/$TAG"
else
  echo ""
  echo "下一步：设置令牌后自动发布"
  echo "  GITEE_TOKEN=你的私人令牌 tools/publish_update.sh --upload"
fi
