#!/usr/bin/env bash
# 一键发版：构建 release → 校验签名指纹 → 哈希 → 不可变 Tag → API 发布 → 上传 → 回读校验。
#
# 设计原则：任何一步校验失败即中止（fail-closed），绝不"先发出去再说"。
#
#   ./scripts/release.sh --version 1.0.1 --notes docs/release-notes-1.0.1.md [--prerelease]
#   ./scripts/release.sh --verify-only path/to/app.apk
#
# 依赖：JDK 17+、Android SDK（aapt2 / apksigner / zipalign）、curl、python3、git。
# 签名材料通过环境变量注入（与 build.gradle.kts 的门禁同名）：
#   M1RACLE_RELEASE_STORE_FILE / _STORE_PASSWORD / _KEY_ALIAS / _KEY_PASSWORD
# GitHub 令牌默认从 git credential manager 取，也可用 GITHUB_TOKEN 覆盖。

set -euo pipefail

REPO_SLUG=""
TAG=""
VERSION=""
NOTES_FILE=""
PRERELEASE="false"
VERIFY_ONLY=""
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-36.0.0}"
# 预期签名身份。换证书时必须显式改这里，否则脚本拒绝发版。
EXPECTED_CERT_SHA256="${EXPECTED_CERT_SHA256:-2d9daba0f92c1d19d290dd58657391a71c16c3008e8a2b90065ba7e8602bf9d1}"
API="https://api.github.com"
UPLOAD_API="https://uploads.github.com"

log()  { printf '\033[36m[release]\033[0m %s\n' "$*"; }
ok()   { printf '\033[32m[  ok  ]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[ warn ]\033[0m %s\n' "$*"; }
die()  { printf '\033[31m[fail ]\033[0m %s\n' "$*" >&2; exit 1; }

usage() { sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --version)    VERSION="${2:-}"; shift 2 ;;
    --notes)      NOTES_FILE="${2:-}"; shift 2 ;;
    --prerelease) PRERELEASE="true"; shift ;;
    --verify-only) VERIFY_ONLY="${2:-}"; shift 2 ;;
    -h|--help)    usage ;;
    *) die "未知参数: $1" ;;
  esac
done

have() { command -v "$1" >/dev/null 2>&1; }
need() { have "$1" || die "缺少依赖: $1"; }

# ── 签名 / 包体校验 ──────────────────────────────────────────────────────────
apksigner_bin() { printf '%s' "${ANDROID_HOME:?ANDROID_HOME 未设置}/build-tools/${BUILD_TOOLS_VERSION}/apksigner"; }
aapt2_bin()     { printf '%s' "${ANDROID_HOME}/build-tools/${BUILD_TOOLS_VERSION}/aapt2"; }

# Windows 上这些是 .bat，MSYS 下需要补后缀
fix_bin() { local b="$1"; if [ -f "$b.bat" ]; then printf '%s' "$b.bat"; else printf '%s' "$b"; fi; }

verify_signature() {
  local apk="$1" out cert
  out="$("$(fix_bin "$(apksigner_bin)")" verify -v --print-certs "$apk" 2>&1)" || die "apksigner 校验失败：$apk"
  grep -q '^Verifies$' <<<"$out" || die "apksigner 未给出 Verifies：$apk"
  grep -q 'Verified using v2 scheme (APK Signature Scheme v2): true' <<<"$out" \
    || die "v2 签名未通过：$apk"
  cert="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$out" | head -1)"
  [ -n "$cert" ] || die "取不到签名证书指纹：$apk"
  [ "$cert" = "$EXPECTED_CERT_SHA256" ] \
    || die "证书指纹不匹配！期望 $EXPECTED_CERT_SHA256，实际 $cert"
  ok "签名 v2 通过，证书指纹 $cert"
}

verify_identity() {
  local apk="$1" want_version="$2" badging pkg vercode vername
  badging="$("$(fix_bin "$(aapt2_bin)")" dump badging "$apk" 2>&1)"
  pkg="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$badging" | head -1)"
  vercode="$(sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" <<<"$badging" | head -1)"
  vername="$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$badging" | head -1)"
  [ "$pkg" = "com.m1racle.reader.pdf" ] || die "包名异常: $pkg"
  if [ -n "$want_version" ] && [ "$vername" != "$want_version" ]; then
    die "版本不符：APK=$vername，参数=$want_version（先改 build.gradle.kts 的 toolVersionName）"
  fi
  grep -q "launchable-activity" <<<"$badging" || die "APK 无启动入口"
  ok "包身份 $pkg / versionCode=$vercode / versionName=$vername"
}

write_checksum() {
  local apk="$1" dir base
  dir="$(cd "$(dirname "$apk")" && pwd)"; base="$(basename "$apk")"
  ( cd "$dir" && sha256sum "$base" > "$base.sha256" )
  ok "SHA-256 $(cut -d' ' -f1 < "$dir/$base.sha256")"
}

# ── GitHub API ───────────────────────────────────────────────────────────────
resolve_token() {
  if [ -n "${GITHUB_TOKEN:-}" ]; then printf '%s' "$GITHUB_TOKEN"; return; fi
  local cred
  cred="$(printf 'protocol=https\nhost=github.com\n\n' | git credential fill 2>/dev/null || true)"
  printf '%s' "$(sed -n 's/^password=//p' <<<"$cred" | head -1)"
}
TOKEN=""
api() {  # api METHOD PATH [curl args...]
  local method="$1" path="$2"; shift 2
  curl -sS --fail-with-body --max-time 120 -X "$method" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Accept: application/vnd.github+json" \
    -H "Content-Type: application/json" \
    "$API$path" "$@"
}
PY="$(have python3 && echo python3 || (have python && echo python))"
[ -n "$PY" ] || die "缺少 python（用于 JSON 处理）"
jgetpy() { "$PY" -c "$@"; }

# ── 模式一：只校验某个 APK ───────────────────────────────────────────────────
if [ -n "$VERIFY_ONLY" ]; then
  [ -f "$VERIFY_ONLY" ] || die "文件不存在: $VERIFY_ONLY"
  log "只校验模式: $VERIFY_ONLY"
  verify_identity "$VERIFY_ONLY" "$VERSION"
  verify_signature "$VERIFY_ONLY"
  ( cd "$(dirname "$VERIFY_ONLY")" && sha256sum "$(basename "$VERIFY_ONLY")" )
  exit 0
fi

# ── 模式二：完整发版 ─────────────────────────────────────────────────────────
[ -n "$VERSION" ] || die "缺少 --version（例：--version 1.0.1）"
TAG="v$VERSION"
need git; need curl; need sha256sum
[ -n "${ANDROID_HOME:-}" ] || die "ANDROID_HOME 未设置"
BUILD_TOOLS_DIR="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION"
[ -d "$BUILD_TOOLS_DIR" ] || die "找不到 build-tools $BUILD_TOOLS_VERSION"
for v in M1RACLE_RELEASE_STORE_FILE M1RACLE_RELEASE_STORE_PASSWORD M1RACLE_RELEASE_KEY_ALIAS M1RACLE_RELEASE_KEY_PASSWORD; do
  [ -n "${!v:-}" ] || die "缺少签名环境变量 $v"
done

REPO_SLUG="$(git remote get-url github 2>/dev/null | sed -E 's#.*github\.com[:/]([^/]+/[^/.]+)(\.git)?$#\1#')"
[ -n "$REPO_SLUG" ] || die "取不到仓库 slug（需要名为 github 的 remote）"
TOKEN="$(resolve_token)"
[ -n "$TOKEN" ] || die "取不到 GitHub 令牌（设置 GITHUB_TOKEN 或登录 git credential manager）"

log "仓库 $REPO_SLUG，目标版本 $TAG，预发布=$PRERELEASE"

# 1. 工作树必须干净：避免把未提交的 WIP 打进release
if [ -n "$(git status --porcelain)" ]; then
  die "工作树不干净，先提交或暂存：
$(git status --porcelain | head -20)"
fi
ok "工作树干净"

# 2. Tag 不可变：已存在即拒绝，不覆盖、不移动
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  die "本地已存在 tag $TAG —— Tag 不可变，请提升版本号"
fi
if git ls-remote --exit-code --tags github "refs/tags/$TAG" >/dev/null 2>&1; then
  die "远端已存在 tag $TAG —— Tag 不可变，请提升版本号"
fi
ok "Tag $TAG 未被占用"

# 3. 构建 release（绝不出 debug）
log "构建 release…"
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -classpath "gradle/wrapper/gradle-wrapper.jar" \
  org.gradle.wrapper.GradleWrapperMain clean assembleRelease --offline --console=plain \
  | tail -5 || die "构建失败"
APK="$(ls build/outputs/apk/release/*.apk 2>/dev/null | head -1)"
[ -n "$APK" ] || die "找不到 release APK（是否被签名门禁拦下？）"
ok "产物 $APK ($(wc -c < "$APK") B)"

verify_identity "$APK" "$VERSION"
verify_signature "$APK"
write_checksum "$APK"

# 4. 提交并创建不可变 tag
HEAD_SHA="$(git rev-parse HEAD)"
git tag -a "$TAG" -m "M1racle PDF Split $VERSION" || die "打 tag 失败"
for i in 1 2 3 4 5; do
  if git push github "$TAG" 2>/dev/null; then break; fi
  warn "推送 tag 失败，第 $i 次重试…"; sleep 3
  [ "$i" = "5" ] && die "推送 tag 连续失败（检查 github.com 可达性）"
done
REMOTE_TAG_SHA="$(api GET "/repos/$REPO_SLUG/git/ref/tags/$TAG" | jgetpy "import json,sys;print(json.load(sys.stdin)['object']['sha'])")"
[ "$REMOTE_TAG_SHA" = "$HEAD_SHA" ] || die "远端 tag 指向 $REMOTE_TAG_SHA，期望 $HEAD_SHA"
ok "Tag $TAG → $HEAD_SHA（远端已确认）"

# 5. 创建 Release
BODY=""
[ -n "$NOTES_FILE" ] && [ -f "$NOTES_FILE" ] && BODY="$(cat "$NOTES_FILE")"
PAYLOAD="$(mktemp)"
jgetpy "import json,sys,io;io.open(sys.argv[1],'w',encoding='utf-8').write(json.dumps({'tag_name':sys.argv[2],'name':sys.argv[3],'body':io.open(sys.argv[4],encoding='utf-8').read() if sys.argv[4] else '','prerelease':sys.argv[5]=='true','draft':False},ensure_ascii=False))" \
  "$PAYLOAD" "$TAG" "M1racle PDF Split $VERSION" "${NOTES_FILE:-}" "$PRERELEASE"
RELEASE_JSON="$(api POST "/repos/$REPO_SLUG/releases" --data-binary @"$PAYLOAD")"
RELEASE_ID="$(jgetpy "import json,sys;print(json.loads(sys.argv[1])['id'])" "$RELEASE_JSON")"
ok "Release 已创建 id=$RELEASE_ID（prerelease=$PRERELEASE）"

# 6. 上传附件
APK_DIR="$(cd "$(dirname "$APK")" && pwd)"; APK_NAME="$(basename "$APK")"
for f in "$APK_NAME" "$APK_NAME.sha256"; do
  curl -sS --fail-with-body --max-time 600 -X POST \
    "$UPLOAD_API/repos/$REPO_SLUG/releases/$RELEASE_ID/assets?name=$f" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/octet-stream" \
    --data-binary "@$APK_DIR/$f" > /dev/null || die "上传失败: $f"
done
ok "附件已上传"

# 7. 上传后回读：尺寸、哈希、签名、tag 指向
TMPDIR_DL="$(mktemp -d)"
ASSETS_JSON="$(api GET "/repos/$REPO_SLUG/releases/$RELEASE_ID/assets")"
ASSET_ID="$(jgetpy "import json,sys;print([a['id'] for a in json.loads(sys.argv[1]) if a['name']==sys.argv[2]][0])" "$ASSETS_JSON" "$APK_NAME")"
curl -sSL --fail --max-time 600 -o "$TMPDIR_DL/$APK_NAME" \
  -H "Authorization: Bearer $TOKEN" -H "Accept: application/octet-stream" \
  "$API/repos/$REPO_SLUG/releases/assets/$ASSET_ID" || die "回读下载失败"
LOCAL_HASH="$(cut -d' ' -f1 < "$APK_DIR/$APK_NAME.sha256")"
REMOTE_HASH="$(sha256sum "$TMPDIR_DL/$APK_NAME" | cut -d' ' -f1)"
[ "$LOCAL_HASH" = "$REMOTE_HASH" ] || die "回读哈希不一致：本地 $LOCAL_HASH / 远端 $REMOTE_HASH"
ok "回读哈希一致 $REMOTE_HASH"
verify_signature "$TMPDIR_DL/$APK_NAME"
POST_TAG_SHA="$(api GET "/repos/$REPO_SLUG/git/ref/tags/$TAG" | jgetpy "import json,sys;print(json.load(sys.stdin)['object']['sha'])")"
[ "$POST_TAG_SHA" = "$HEAD_SHA" ] || die "Tag 在发版过程中被移动！"
ok "Tag 仍指向 $HEAD_SHA"

cat <<EOF

──────────────── 发版完成 ────────────────
仓库      https://github.com/$REPO_SLUG
Release   https://github.com/$REPO_SLUG/releases/tag/$TAG
版本      $VERSION (tag $TAG → ${HEAD_SHA:0:9})
APK       $APK_NAME ($(wc -c < "$APK_DIR/$APK_NAME") B)
SHA-256   $REMOTE_HASH
证书指纹  $EXPECTED_CERT_SHA256
预发布    $PRERELEASE
回读校验  哈希一致 / 签名 v2 通过 / tag 未移动
──────────────────────────────────────────
EOF
