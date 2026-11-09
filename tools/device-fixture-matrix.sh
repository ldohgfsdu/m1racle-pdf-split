#!/usr/bin/env bash
# 真机 AUTO 三态矩阵：逐个投喂样本，采集 probe 决策日志。
#
#   ./tools/device-fixture-matrix.sh 01-healthy.pdf 02-big-content-tail.pdf ...
#
# 前提：样本已推到 /sdcard/Download/fixtures/，媒体库已索引，设备已连 adb。
# 走 SAF 选择器（与真实用户一致）；file:// 直达需要 MANAGE_EXTERNAL_STORAGE，
# 本机未授予，也不为了跑测试去改权限。
set -uo pipefail

PKG="com.m1racle.reader.pdf"
ACTIVITY="$PKG/com.m1racle.reader.pdfsplit.MainActivity"
DUMP="/sdcard/dump.xml"

log() { printf '%s\n' "$*"; }

# 在 wait_xy 里被赋值
XY=""

# 轮询等待某个文本出现，成功则把中心点写进 XY。
#
# 注意：`uiautomator dump` 输出是**单行 XML**，不能用 cut/grep 逐行切。
# 早前用 `tr '>' '\n'` 其实没切开，grep 一下把整份文档的 bounds 全捞出来了，
# 再取"面积最小"就选中了左上角返回箭头，点击全打在错误位置。
# 这里按 <node> 逐节点解析，取 text 精确相等里面积最小的那个。
wait_xy() {
  local want="$1" tries="${2:-12}" i
  for i in $(seq 1 "$tries"); do
    if adb shell uiautomator dump "$DUMP" >/dev/null 2>&1; then
      XY="$(adb shell cat "$DUMP" 2>/dev/null | python -c '
import re, sys
want = sys.argv[1]
data = sys.stdin.read()
best = None
for chunk in data.split("<node"):
    m = re.search(r"text=\"([^\"]*)\"", chunk)
    if not m or m.group(1) != want:
        continue
    b = re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", chunk)
    if not b:
        continue
    x1, y1, x2, y2 = (int(v) for v in b.groups())
    area = (x2 - x1) * (y2 - y1)
    if best is None or area < best[0]:
        best = (area, (x1 + x2) // 2, (y1 + y2) // 2)
if best:
    print(best[1], best[2])
' "$want")"
      if [ -n "$XY" ]; then return 0; fi
    fi
    sleep 2
  done
  XY=""
  return 1
}

# shellcheck disable=SC2086
tap_xy() { adb shell input tap $XY; }

# 等它出现再点
tap_when() {
  local want="$1" tries="${2:-12}"
  wait_xy "$want" "$tries" || return 1
  tap_xy
  return 0
}

ensure_foreground() {
  local i top
  for i in 1 2 3 4; do
    top="$(adb shell dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity)"
    case "$top" in
      *pdfsplit.MainActivity*) return 0 ;;
    esac
    adb shell input keyevent KEYCODE_BACK
    sleep 1
  done
  adb shell am start -n "$ACTIVITY" >/dev/null 2>&1
  sleep 3
  return 0
}

feed() {
  local name="$1"
  adb shell am force-stop "$PKG"
  ensure_foreground
  adb logcat -c
  adb shell am start -n "$ACTIVITY" >/dev/null 2>&1
  sleep 3

  if ! tap_when "▶  选择 PDF" 10; then
    tap_when "⇄  更换" 5 || { log "$name 无法打开选择器"; return 1; }
  fi

  # 优先在「最近」里直接点（样本刚推上去，媒体库已索引）；
  # 找不到才走 浏览 → Download → fixtures。
  if ! tap_when "$name" 10; then
    tap_when "浏览" 6 || { log "$name 没有浏览标签"; return 1; }
    tap_when "Download" 8 || { log "$name 找不到 Download"; return 1; }
    tap_when "fixtures" 8 || { log "$name 找不到 fixtures"; return 1; }
    tap_when "$name" 8 || { log "$name 列表里找不到该文件"; return 1; }
  fi
  tap_when "确定" 6 || true

  local i out
  for i in $(seq 1 15); do
    sleep 2
    out="$(adb logcat -d -s M1PDF 2>/dev/null | grep 'probe ' | tail -1)"
    [ -n "$out" ] && break
  done
  if [ -n "$out" ]; then
    printf '%-24s %s\n' "$name" "$(printf '%s' "$out" | sed -n 's/.*decision=\[\([^]]*\)\].*/\1/p')"
  else
    local err
    err="$(adb shell uiautomator dump "$DUMP" >/dev/null 2>&1; adb shell cat "$DUMP" 2>/dev/null \
      | tr '>' '>\n' | grep -o 'text="[^"]*"' | grep -v 'text=""' \
      | grep -E '读不出|加密|损坏|失败' | head -1)"
    printf '%-24s NO-PROBE %s\n' "$name" "${err:-}"
  fi
}

for f in "$@"; do
  feed "$f"
done
