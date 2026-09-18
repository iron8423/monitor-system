#!/bin/bash
# 验收 17：审计留痕的完整性（复查清单 P1-8，V23）。
#
#   改造前 audit_log 只记「谁、何时、对什么、做了什么（入参）」，回答不了两个排查时
#   最常问的问题：「这一行原来是什么值」「有没有人试图改过、被拒了」。本套件钉四件事：
#     ① 新建 -> after_json 有值、before_json 为空、result=SUCCESS
#     ② 更新 -> before_json 与 after_json 能看出改了哪个字段
#     ③ 进了方法体但被业务规则拒绝 -> result=FAILED + 原因（标定被判定为遮挡）
#     ④ 越权写尝试（@PreAuthorize 在方法之前拒绝）-> 也留痕（RestAccessDeniedHandler）
#   外加两条噪声收敛：读接口的 403 **不**记；非管理员读不到审计列表。
#
#   为什么审计行不回收：审计表按设计只增不减（没有删除端点，也不该有）。
#   本套件留下的行都带 RUN_ID，翻到时候认得出是谁写的；这是它的成本，写在明处。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

TOKEN=$(login)
AUTH="Authorization: Bearer $TOKEN"
OP_TOKEN=$(login_as operator)
OP_AUTH="Authorization: Bearer $OP_TOKEN"
[ -n "$OP_TOKEN" ] || { printf '%s未能登录 operator%s\n' "$C_RED" "$C_OFF" >&2; exit 2; }

# 审计行挑选统一走 support/audit_row.py（多行 python 塞进 bash 只会越来越难读）
row_of() { python3 "$HERE/support/audit_row.py" "$BASE" "$TOKEN" "$@"; }
# 取某个字段；缺字段打印 None，找不到行打印 <无>
field_of() {
  python3 -c "
import sys, json
raw = sys.stdin.read().strip()
if raw == '<无>':
    print('<无>')
else:
    print(json.dumps(json.loads(raw).get('$1'), ensure_ascii=False))
"
}

section "⓪ 夹具：项目 1 下的临时测点"
NP="P-AUD-$RUN_ID"
PID=$(curl -s -X POST "$BASE/points" -H "$AUTH" -H "$JSON" \
      -d "{\"objectId\":1,\"code\":\"$NP\",\"name\":\"审计验收临时测点\",\"type\":\"POINT_DEFORMATION\",\"enabled\":true}" \
      | data_of "['id']")
check "临时测点已建" "true" "$([ -n "$PID" ] && [ "$PID" != "None" ] && echo true || echo false)"
info "pointId=$PID code=$NP"

section "① 新建：after 有值、before 为空、result=SUCCESS"
ROW=$(row_of MonitorPoint 创建 "$PID")
check "新建留痕存在且 result=SUCCESS" '"SUCCESS"' "$(printf '%s' "$ROW" | field_of result)"
check "before_json 为空（本来就没有这一行）" "null" "$(printf '%s' "$ROW" | field_of beforeJson)"
check_contains "after_json 含新建的点号" "$NP" "$(printf '%s' "$ROW" | field_of afterJson)"
check "detail 仍保留入参（老口径不破）" "true" \
  "$(printf '%s' "$ROW" | python3 -c "import sys,json;r=sys.stdin.read().strip();print('true' if r!='<无>' and json.loads(r).get('detail') is not None else 'false')")"

section "② 更新：before/after 都在，且能看出改了哪个字段"
NEW_NAME="审计验收改名-$RUN_ID"
check "更新成功" "200" \
  "$(http_code -X PUT "$BASE/points/$PID" -H "$AUTH" -H "$JSON" -d "{\"name\":\"$NEW_NAME\"}")"
UP=$(row_of MonitorPoint 更新 "$PID")
check "更新留痕 result=SUCCESS" '"SUCCESS"' "$(printf '%s' "$UP" | field_of result)"
check_contains "before_json 里是旧名字" "审计验收临时测点" "$(printf '%s' "$UP" | field_of beforeJson)"
check_contains "after_json 里是新名字" "$NEW_NAME" "$(printf '%s' "$UP" | field_of afterJson)"
# 只看 after 的话，"原来是什么"仍然无从回答——这一条就是 P1-8 的核心
check "before 与 after 不是同一份内容" "true" \
  "$(printf '%s' "$UP" | python3 -c "import sys,json;r=sys.stdin.read().strip();print('true' if r!='<无>' and json.loads(r).get('beforeJson') != json.loads(r).get('afterJson') else 'false')")"

section "③ 被业务规则拒绝的写操作也留痕（标定：视线被判定为遮挡）"
DEV="dev-aud-$RUN_ID"
DID=$(curl -s -X POST "$BASE/devices" -H "$AUTH" -H "$JSON" \
      -d "{\"code\":\"$DEV\",\"name\":\"审计验收临时雷达\",\"type\":\"MILLIMETER_WAVE_RADAR\",
           \"longitude\":113.0500000,\"latitude\":23.7200000,\"altitude\":60.000,
           \"antennaHeightM\":10.000,\"headingDegrees\":0,\"pitchDegrees\":0,
           \"detectionRangeM\":265.000,\"halfAngleDegrees\":30.0000,\"verticalHalfAngleDegrees\":15.0000,
           \"status\":\"ONLINE\"}" | data_of "['id']")
curl -s -o /dev/null -X POST "$BASE/devices/$DID/points/$PID" -H "$AUTH"
check "遮挡标定被拒 -> 400" "400" \
  "$(http_code -X PUT "$BASE/devices/$DID/points/$PID/calibration" -H "$AUTH" -H "$JSON" \
     -d '{"targetCode":"TGT-AUD","azimuthDegrees":10.0,"elevationDegrees":5.0,"slantRangeM":20.0,"reflectorHeightM":1.0,"minimumClearanceM":1.0,"lineOfSight":false,"note":"审计验收：故意让校验失败"}')"
FAIL_ROW=$(row_of Device 标定雷达测点 - -)
check "被拒的标定也写了审计（result=FAILED）" '"FAILED"' "$(printf '%s' "$FAIL_ROW" | field_of result)"
check_contains "原因写清了「为什么被拒」" "遮挡" "$(printf '%s' "$FAIL_ROW" | field_of errorMessage)"
check "被拒的行没有 after 值" "null" "$(printf '%s' "$FAIL_ROW" | field_of afterJson)"

section "④ 越权写尝试也留痕（@PreAuthorize 在方法之前拒绝）"
check "operator 建测点 -> 403" "403" \
  "$(http_code -X POST "$BASE/points" -H "$OP_AUTH" -H "$JSON" \
     -d "{\"objectId\":1,\"code\":\"P-AUDX-$RUN_ID\",\"name\":\"越权尝试\",\"type\":\"POINT_DEFORMATION\"}")"
DENY=$(row_of HttpRequest 越权尝试 /api/v1/points operator)
check "越权写尝试有审计行且 result=FAILED" '"FAILED"' "$(printf '%s' "$DENY" | field_of result)"
check_contains "attempt 里带着方法与前缀" "POST /api/v1/points" "$(printf '%s' "$DENY" | field_of detail)"

section "⑤ 噪声收敛：读接口的 403 不记，非管理员读不到审计"
check "operator 读项目 2 -> 403（数据范围）" "403" "$(http_code "$BASE/projects/2" -H "$OP_AUTH")"
check "读 403 没有留下 HttpRequest 审计行" "0" \
  "$(curl -s "$BASE/audit-logs?pageNum=1&pageSize=50&targetType=HttpRequest" -H "$AUTH" | python3 -c "import sys,json;rs=json.load(sys.stdin)['data']['records'];print(len([r for r in rs if r.get('targetId')=='/api/v1/projects/2']))")"
check "operator 读审计列表 -> 403" "403" "$(http_code "$BASE/audit-logs" -H "$OP_AUTH")"
check "无 JWT 读审计列表 -> 401" "401" "$(http_code "$BASE/audit-logs")"

section "⑥ 回收临时数据（审计行按设计保留）"
curl -s -o /dev/null -X DELETE "$BASE/devices/$DID/points/$PID" -H "$AUTH"
curl -s -o /dev/null -X DELETE "$BASE/points/$PID" -H "$AUTH"
curl -s -o /dev/null -X DELETE "$BASE/devices/$DID" -H "$AUTH"
info "已回收临时测点与设备；本套件新增的审计行保留（审计表只增不减）"

summary
