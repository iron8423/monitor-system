#!/bin/bash
# 验收 06：影像上传 / 列表 / 读取（验收脚本第 6 条）。
#   契约依据：《B侧接口契约_M0》§7（mediaId 为不透明字符串编码、列表返裸数组）。
#   载荷安全一并覆盖：客户端文件名不落盘（防路径穿越）、非图片拒收。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"
ROOT="$(cd "$HERE/../.." && pwd)"
MEDIA_DIR="$ROOT/backend/data/media"

TOKEN=$(login)
AUTH="Authorization: Bearer $TOKEN"
PID=1        # 种子测点 P-HK01

WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
python3 -c "
import base64, sys
open('$WORK/px.png','wb').write(base64.b64decode(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='))
open('$WORK/notimage.txt','w').write('i am not an image')
"

section "① 上传图片 -> 返回不透明编码 mediaId（契约 §7）"
UP=$(curl -s -X POST "$BASE/media" -H "$AUTH" \
     -F "file=@$WORK/px.png;type=image/png" -F "pointId=$PID" \
     -F "takenAt=2026-08-27T10:00:00+08:00" -F "note=验收-边坡巡检A区")
MID=$(printf '%s' "$UP" | data_of "['mediaId']")
info "mediaId = $MID"
check_contains "mediaId 形如 M<数字>（不透明编码）" "M" "$MID"
check "mediaId 匹配 ^M[0-9]+$" "1" "$(printf '%s' "$MID" | grep -Ec '^M[0-9]+$')"
check "url 指向 content 端点" "/api/v1/media/$MID/content" "$(printf '%s' "$UP" | data_of "['url']")"
check "关联到测点" "$PID" "$(printf '%s' "$UP" | data_of "['pointId']")"
check_contains "objectKey 落在该测点目录下" "media/P-HK01/" "$(printf '%s' "$UP" | data_of "['objectKey']")"

section "② 客户端文件名不落盘（防路径穿越）"
EVIL=$(curl -s -X POST "$BASE/media" -H "$AUTH" \
       -F "file=@$WORK/px.png;type=image/png;filename=../../evil.png" -F "pointId=$PID")
EOK=$(printf '%s' "$EVIL" | data_of "['objectKey']")
info "伪装文件名的 objectKey = $EOK"
check "未采用客户端文件名" "0" "$(printf '%s' "$EOK" | grep -c 'evil' )"
check_contains "仍落在测点目录下" "media/P-HK01/" "$EOK"
check "实际未写出越界文件" "0" "$(find "$ROOT" -name 'evil.png' 2>/dev/null | wc -l | tr -d ' ')"

section "③ 拒收非图片 / 不存在的测点"
check "text/plain -> 400" "400" "$(http_code -X POST "$BASE/media" -H "$AUTH" \
  -F "file=@$WORK/notimage.txt;type=text/plain" -F "pointId=$PID")"
check "不存在的测点 -> 404" "404" "$(http_code -X POST "$BASE/media" -H "$AUTH" \
  -F "file=@$WORK/px.png;type=image/png" -F "pointId=99999")"
check "无 JWT -> 401" "401" "$(http_code -X POST "$BASE/media" -F "file=@$WORK/px.png;type=image/png" -F "pointId=$PID")"

section "④ 测点影像列表（非分页，裸数组）"
LIST=$(curl -s "$BASE/points/$PID/media" -H "$AUTH")
check "data 是数组而非 {items:[]}" "True" "$(printf '%s' "$LIST" | python3 -c "
import sys,json;print(isinstance(json.load(sys.stdin)['data'], list))")"
check_contains "本次上传出现在列表里" "$MID" "$(printf '%s' "$LIST" | python3 -c "
import sys,json;print([m['mediaId'] for m in json.load(sys.stdin)['data']])")"

section "⑤ 影像内容读取（验收第 6 条：详情页要看得到图）"
check "带 Authorization 头 -> 200" "200" "$(http_code "$BASE/media/$MID/content" -H "$AUTH")"
curl -s -o "$WORK/got.png" "$BASE/media/$MID/content" -H "$AUTH"
if cmp -s "$WORK/px.png" "$WORK/got.png"; then pass "取回内容与上传字节一致"; else fail "取回内容与上传不一致" "size $(wc -c < "$WORK/got.png")"; fi
CT=$(curl -s -o /dev/null -w '%{content_type}' "$BASE/media/$MID/content" -H "$AUTH")
check_contains "Content-Type 为图片" "image/png" "$CT"
check "带 ?token=（<img> 场景）-> 200" "200" "$(http_code "$BASE/media/$MID/content?token=$TOKEN")"
check "不带凭证 -> 401" "401" "$(http_code "$BASE/media/$MID/content")"
check "不存在的影像 -> 404" "404" "$(http_code "$BASE/media/M999999/content" -H "$AUTH")"
check "非法编码 -> 404" "404" "$(http_code "$BASE/media/not-a-code/content" -H "$AUTH")"

section "⑥ 落盘位置"
if [ -d "$MEDIA_DIR" ]; then
  N=$(find "$MEDIA_DIR" -type f | wc -l | tr -d ' ')
  pass "上传目录存在，含 $N 个文件"
  info "目录：$MEDIA_DIR"
else
  fail "上传目录不存在" "$MEDIA_DIR"
fi

summary
