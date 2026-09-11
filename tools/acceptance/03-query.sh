#!/bin/bash
# 验收 03：测点数据查询（latest / series）—— 时间口径、分桶、错误码。
#   覆盖验收脚本第 1 条「点击测点看最新值」数据面；契约依据《B侧接口契约_M0》§3。
#   造数落在**本次新建的临时测点**上，因此断言可以精确到条数（重跑不会串上一次的数据）。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

TOKEN=$(login)
AUTH="Authorization: Bearer $TOKEN"

section "① 备数据：临时测点上跨 3 天各来一条（便于验证分桶）"
NP="P-QRY-$RUN_ID"
PID=$(curl -s -X POST "$BASE/points" -H "$AUTH" -H "$JSON" \
      -d "{\"objectId\":1,\"code\":\"$NP\",\"name\":\"查询验收临时测点\",\"type\":\"POINT_DEFORMATION\",\"enabled\":true}" \
      | data_of "['id']")
info "临时测点 $NP -> pointId=$PID"
for d in 25 26 27; do
  ingest2 "qry-$RUN_ID-$d" "$NP" "2026-08-$d"T10:00:00+08:00 "$((d - 24))" "0.$((d - 24))" >/dev/null
done
info "已上报 3 条：2026-08-25/26/27，defo_mm = 1/2/3"

section "② latest：同刻测项重组 + ISO 带时区 + position/signal"
LAT=$(curl -s "$BASE/points/$PID/latest" -H "$AUTH")
check "pointId 用数值" "$PID" "$(printf '%s' "$LAT" | data_of "['pointId']")"
check "pointCode 用字符串" "$NP" "$(printf '%s' "$LAT" | data_of "['pointCode']")"
check "取到最新一条 defo_mm=3" "3.0" "$(printf '%s' "$LAT" | data_of "['latest']['defo_mm']")"
check_contains "collectTime 为 ISO8601 带时区" "2026-08-27T10:00:00" \
  "$(printf '%s' "$LAT" | data_of "['latest']['collectTime']")"
check_contains "position 来自 attributes" "angleDeg" "$(printf '%s' "$LAT" | python3 -c "
import sys,json;print(json.load(sys.stdin)['data']['latest'].get('position'))")"
check "signal 来自 attributes" "0.9" "$(printf '%s' "$LAT" | data_of "['latest']['signal']")"

section "③ series 默认 defo_mm / raw：应恰好 3 点，值 1/2/3"
SER=$(curl -s "$BASE/points/$PID/series" -H "$AUTH")
check "默认 metricCode" "defo_mm" "$(printf '%s' "$SER" | data_of "['metricCode']")"
check "点数" "3" "$(printf '%s' "$SER" | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']['points']))")"
check "值序列（升序）" "[1.0, 2.0, 3.0]" "$(printf '%s' "$SER" | python3 -c "
import sys,json;print([p['v'] for p in json.load(sys.stdin)['data']['points']])")"
check_contains "t 为 ISO8601 带 +08:00" "+08:00" \
  "$(printf '%s' "$SER" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['points'][0]['t'])")"

section "④ 分桶 hour / day：3 天 3 条 -> 各 3 桶"
for g in hour day; do
  check "granularity=$g 桶数" "3" "$(curl -s "$BASE/points/$PID/series?granularity=$g" -H "$AUTH" \
    | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']['points']))")"
done

section "⑤ from/to 三种时间写法都应接受"
while IFS='|' read -r q label; do
  check "$label" "3" "$(curl -s "$BASE/points/$PID/series?$q" -H "$AUTH" \
    | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']['points']))")"
done <<EOF
from=2026-08-25T00:00:00%2B08:00&to=2026-08-28T00:00:00%2B08:00|ISO8601 带时区
from=2026-08-25T00:00:00&to=2026-08-28T00:00:00|ISO8601 本地时间
from=2026-08-25%2000:00:00&to=2026-08-28%2000:00:00|空格分隔
EOF

section "⑥ 窗口收敛：只取 26 日 -> 1 点"
check "from/to 命中 1 点" "1" "$(curl -s "$BASE/points/$PID/series?from=2026-08-26T00:00:00%2B08:00&to=2026-08-26T23:59:59%2B08:00" -H "$AUTH" \
  | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']['points']))")"

section "⑦ 单位口径（种子测点 6 有档案 metric 行）"
check "rate_mm_d 单位" "mm/d" "$(curl -s "$BASE/points/6/series?metricCode=rate_mm_d" -H "$AUTH" | data_of "['unit']")"
check "defo_mm 单位" "mm" "$(curl -s "$BASE/points/6/series" -H "$AUTH" | data_of "['unit']")"
info "注：档案取到的单位与兜底值恰好同值，此条只验口径一致，不区分来源"

section "⑧ 入参校验与错误码"
check "非法时间 -> 400" "400" "$(http_code "$BASE/points/$PID/series?from=not-a-time" -H "$AUTH")"
check "非法 granularity -> 400" "400" "$(http_code "$BASE/points/$PID/series?granularity=week" -H "$AUTH")"
check "不存在测点 latest -> 404" "404" "$(http_code "$BASE/points/99999/latest" -H "$AUTH")"
check "不存在测点 series -> 404" "404" "$(http_code "$BASE/points/99999/series" -H "$AUTH")"
check "无 JWT -> 401" "401" "$(http_code "$BASE/points/$PID/latest")"

section "⑨ 同刻多行：latest 与 summary 必须取到同一行"
# 同一点、同一 collect_time、两次不同 messageId 的上报会落两行——幂等键是 device+message，
# 不含 collect_time，所以这不是「重复上报」。此时「最新一行」必须只有一个判据，
# 否则 /points/{id}/latest 与 /projects/{id}/summary 会各自取到不同行，同一测点两个值。
#
# 夹具自带 project→scene→object→point 四级，是为了让断言**自足**：该临时项目下只有这一个测点，
# summary 的最大形变必然等于它，不依赖库里其它测点当时恰好是什么值。
# （不必担心污染正式项目：删测点是逻辑删除 deleted=1，全局逻辑删除配置会让它和它的
#   measurement 行对所有查询不可见——所以挂在正式项目下也不会顶掉别人的最大形变。
#   这里隔离纯粹是为了断言不依赖外部状态。）
T_PRJ=$(curl -s -X POST "$BASE/projects" -H "$AUTH" -H "$JSON" \
        -d "{\"organizationId\":1,\"name\":\"口径一致性验收\",\"code\":\"PRJ-DUP-$RUN_ID\"}" | data_of "['id']")
T_SCN=$(curl -s -X POST "$BASE/scenes" -H "$AUTH" -H "$JSON" \
        -d "{\"projectId\":$T_PRJ,\"name\":\"临时场景\",\"type\":\"SLOPE\"}" | data_of "['id']")
T_OBJ=$(curl -s -X POST "$BASE/objects" -H "$AUTH" -H "$JSON" \
        -d "{\"sceneId\":$T_SCN,\"name\":\"临时对象\",\"type\":\"SLOPE_BODY\"}" | data_of "['id']")
D_PID=$(curl -s -X POST "$BASE/points" -H "$AUTH" -H "$JSON" \
        -d "{\"objectId\":$T_OBJ,\"code\":\"P-DUP-$RUN_ID\",\"name\":\"同刻多行临时测点\",\"type\":\"POINT_DEFORMATION\",\"enabled\":true}" \
        | data_of "['id']")
D_CT="2026-09-11T09:00:00+08:00"
ingest2 "dup-$RUN_ID-a" "P-DUP-$RUN_ID" "$D_CT" 111.1 0.1 >/dev/null
ingest2 "dup-$RUN_ID-b" "P-DUP-$RUN_ID" "$D_CT" 222.2 0.2 >/dev/null
info "同刻两行已上报：先 111.1、后 222.2（pointId=$D_PID）"

L_DEFO=$(curl -s "$BASE/points/$D_PID/latest" -H "$AUTH" | data_of "['latest']['defo_mm']")
S_DEFO=$(curl -s "$BASE/projects/$T_PRJ/summary" -H "$AUTH" | data_of "['maxDeformationMm']")
check "latest 取后写库的那条" "222.2" "$L_DEFO"
check "summary 与 latest 取到同一行" "$L_DEFO" "$S_DEFO"

for r in "points/$D_PID" "objects/$T_OBJ" "scenes/$T_SCN" "projects/$T_PRJ"; do
  C=$(http_code -X DELETE "$BASE/$r" -H "$AUTH")
  [ "$C" = "200" ] || info "临时 $r 未回收（HTTP $C），可忽略"
done
info "已回收临时项目链"

DEL=$(http_code -X DELETE "$BASE/points/$PID" -H "$AUTH")
[ "$DEL" = "200" ] && info "已回收临时测点" || info "临时测点未回收（HTTP $DEL），可忽略"

summary
