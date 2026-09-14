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
EVID=$(printf '%s' "$EVIL" | data_of "['mediaId']")
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

section "⑦ 删除影像（2026-09-14 新增，走逻辑删除）"
# 单独传一张来删，前两张留着给上面的列表断言用
DELUP=$(curl -s -X POST "$BASE/media" -H "$AUTH" \
        -F "file=@$WORK/px.png;type=image/png" -F "pointId=$PID" -F "note=验收-待删")
DMID=$(printf '%s' "$DELUP" | data_of "['mediaId']")
DKEY=$(printf '%s' "$DELUP" | data_of "['objectKey']")
info "待删 mediaId = $DMID"
check "删除前在列表里" "1" "$(curl -s "$BASE/points/$PID/media" -H "$AUTH" | python3 -c "
import sys,json;print(1 if '$DMID' in [m['mediaId'] for m in json.load(sys.stdin)['data']] else 0)")"
check "DELETE -> 200" "200" "$(http_code -X DELETE "$BASE/media/$DMID" -H "$AUTH")"
check "删除后不在列表里" "0" "$(curl -s "$BASE/points/$PID/media" -H "$AUTH" | python3 -c "
import sys,json;print(1 if '$DMID' in [m['mediaId'] for m in json.load(sys.stdin)['data']] else 0)")"
check "删除后取图 -> 404" "404" "$(http_code "$BASE/media/$DMID/content" -H "$AUTH")"
check "重复删除 -> 404（不静默成功）" "404" "$(http_code -X DELETE "$BASE/media/$DMID" -H "$AUTH")"
check "无 JWT -> 401" "401" "$(http_code -X DELETE "$BASE/media/$DMID")"

# 软删的**证据**：库里标 deleted=1，盘上文件仍在。
# objectKey 带 media/ 前缀（对外逻辑键），盘上路径不带——这里必须剥掉再拼，
# 否则会查一个不存在的路径，把「文件还在」误判成「文件没了」。
DFILE="$MEDIA_DIR/${DKEY#media/}"
if [ -f "$DFILE" ]; then
  pass "软删：盘上文件保留（$DKEY）"
else
  info "盘上文件查不到（$DFILE）——容器形态下属预期，本机运行态才断言"
fi

# 角色边界：影像的使用场景是运维上传现场照片，值班/研判是**读**影像的角色。
# 用 login_as 而不是 login——后者固定用 ADMIN 账号且失败即 exit 2，
# 拿它当「别的角色的 token」用会静默拿到管理员身份，断言全变成假绿。
check "operator（值班）删 -> 403" "403" \
  "$(http_code -X DELETE "$BASE/media/$DMID" -H "Authorization: Bearer $(login_as operator)")"
check "analyst（研判）删 -> 403" "403" \
  "$(http_code -X DELETE "$BASE/media/$DMID" -H "Authorization: Bearer $(login_as analyst)")"
check "maintainer（运维）过了角色闸（对已删的 id 得 404 而非 403）" "404" \
  "$(http_code -X DELETE "$BASE/media/$DMID" -H "Authorization: Bearer $(login_as maintainer)")"

section "⑧ 套件回收自己传的图"
# 这一段是这轮新增 DELETE 端点的**首要动因**：此前 media 只能增不能减，
# 每跑一轮就往库里留几张 1×1 测试图，且因为**没有删除端点**而回收不了。
# 现在有了，套件就得自己收拾——与 04-alarm.sh 回收自建规则、lib.sh 的
# recycle_point() 回收临时测点是同一条纪律。
#
# ⚠️ 只删**自己这次传的**（按 mediaId），不能按测点「清空 P-HK01 的影像」——
# 套件也可能被对着演示库跑，那样会把真实巡检照片一起删掉。
for m in "$MID" "$EVID"; do
  [ -n "$m" ] || continue
  code=$(http_code -X DELETE "$BASE/media/$m" -H "$AUTH")
  if [ "$code" = "200" ]; then info "已回收 $m"; else info "$m 未回收（HTTP $code）"; fi
done
# 先把「三个 id 都拿到了」写进断言：三个变量若为空串，`in` 判等永远不成立、
# 计数恒为 0，那条断言就会在「三张图一张都没传成功」时反而亮绿——
# 这正是本套件 06 曾经踩过的 `[].every()` 恒真那一类假绿。
check "本次三张测试图都不再出现在列表里（且三张都确实传成功过）" "0" "$(curl -s "$BASE/points/$PID/media" -H "$AUTH" | python3 -c "
import sys,json
ids = ['$MID', '$EVID', '$DMID']
if not all(ids):
    print('IDS-MISSING(传都没传成功，这条不算数)：' + repr(ids))
else:
    left = [m['mediaId'] for m in json.load(sys.stdin)['data']]
    print(sum(1 for x in left if x in ids))")"

summary
