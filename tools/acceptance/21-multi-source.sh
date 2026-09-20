#!/bin/bash
# 验收 21：多源权威（复查清单 P1-2）。
#
#   一个测点可以被多台设备观测，而"当前值"只能有一个。改造前的判据是
#   `collect_time DESC, id DESC`——**与来源无关**：两台雷达看同一个点时，谁的值算当前值
#   取决于谁的消息晚到几毫秒，采样更慢或链路更慢的那台会持续盖掉快的那台，
#   而界面上只有一个数、看不出来。
#
#   现在的判据：**优先级最小的来源里、时间最新的一条**（优先级数值越小越权威）。
#   本套件钉五件事：
#     ① 都没配优先级（默认 100）时行为与改造前完全一致（按时间）；
#     ② 把 A 设成权威来源后，即使 B 的时间更新，当前值也取 A 的；
#     ③ 权威来源这一刻没有数据时**回退**到有时间的那台，而不是给空值；
#     ④ 单点 latest / 项目批量 latest / 概览最大形变三处**同判据**；
#     ⑤ 校验与权限（取值边界、未绑定、角色、审计）。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

TOKEN=$(login)
AUTH="Authorization: Bearer $TOKEN"
OP_TOKEN=$(login_as operator)
OP_AUTH="Authorization: Bearer $OP_TOKEN"
[ -n "$OP_TOKEN" ] || { printf '%s未能登录 operator%s\n' "$C_RED" "$C_OFF" >&2; exit 2; }

TS() { python3 "$HERE/support/ts_offset.py" "$@"; }

# 造一台带几何的临时雷达（几何齐全才不会在绑定那条路上被范围校验挡住）
mk_device() {
  local code="$1" name="$2"
  curl -s -X POST "$BASE/devices" -H "$AUTH" -H "$JSON" \
    -d "{\"code\":\"$code\",\"name\":\"$name\",\"type\":\"MILLIMETER_WAVE_RADAR\",
         \"longitude\":113.0500000,\"latitude\":23.7200000,\"altitude\":60.000,
         \"antennaHeightM\":10.000,\"headingDegrees\":0,\"pitchDegrees\":0,
         \"detectionRangeM\":265.000,\"halfAngleDegrees\":30.0000,\"verticalHalfAngleDegrees\":15.0000,
         \"status\":\"ONLINE\"}" | data_of "['id']"
}

section "⓪ 夹具：一个临时测点 + 两台临时雷达，都绑定到它"
NP="P-SRC-$RUN_ID"
DA="dev-srcA-$RUN_ID"
DB="dev-srcB-$RUN_ID"
PID=$(curl -s -X POST "$BASE/points" -H "$AUTH" -H "$JSON" \
      -d "{\"objectId\":1,\"code\":\"$NP\",\"name\":\"多源验收临时测点\",\"type\":\"POINT_DEFORMATION\",\"enabled\":true}" \
      | data_of "['id']")
DIDA=$(mk_device "$DA" "多源验收雷达A")
DIDB=$(mk_device "$DB" "多源验收雷达B")
check "两台临时雷达已建" "true" "$([ -n "$DIDA" ] && [ -n "$DIDB" ] && echo true || echo false)"
check "A 绑定成功" "200" "$(http_code -X POST "$BASE/devices/$DIDA/points/$PID" -H "$AUTH")"
check "B 绑定成功" "200" "$(http_code -X POST "$BASE/devices/$DIDB/points/$PID" -H "$AUTH")"
info "pointId=$PID；设备 A=$DIDA（$DA）/ B=$DIDB（$DB）"

section "① 默认优先级（都 100）：仍按时间取最新——与改造前一致"
ingest_id "src-a-$RUN_ID" "$NP" "$(TS --minutes -10)" '"defo_mm":1.0' "$DA" >/dev/null
ingest_id "src-b-$RUN_ID" "$NP" "$(TS --minutes -5)" '"defo_mm":9.0' "$DB" >/dev/null
LAT=$(curl -s "$BASE/points/$PID/latest" -H "$AUTH")
check "取的是时间更新的 B（9.0）" "9.0" "$(printf '%s' "$LAT" | data_of "['latest']['defo_mm']")"
check "回应里带出值是谁报的" "$DB" "$(printf '%s' "$LAT" | data_of "['sourceDeviceCode']")"
check "回应里带出优先级（默认 100）" "100" "$(printf '%s' "$LAT" | data_of "['sourcePriority']")"
check "多来源时带出完整来源清单" "2" \
  "$(printf '%s' "$LAT" | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data'].get('sources') or []))")"

section "② 把 A 设为权威来源（优先级 10）：当前值改取 A，哪怕 B 的时间更新"
check "设置成功且回显 10" "10" \
  "$(curl -s -X PUT "$BASE/devices/$DIDA/points/$PID/source-priority" -H "$AUTH" -H "$JSON" -d '{"sourcePriority":10}' | data_of "['sourcePriority']")"
LAT=$(curl -s "$BASE/points/$PID/latest" -H "$AUTH")
check "当前值变成 A 的 1.0（消息早 5 分钟也照样赢）" "1.0" "$(printf '%s' "$LAT" | data_of "['latest']['defo_mm']")"
check "来源标注为 A" "$DA" "$(printf '%s' "$LAT" | data_of "['sourceDeviceCode']")"
check "优先级标注为 10" "10" "$(printf '%s' "$LAT" | data_of "['sourcePriority']")"

section "③ 动态改判：把 A 降到 200（比默认还差）→ 当前值回到 B"
check "设置成功且回显 200" "200" \
  "$(curl -s -X PUT "$BASE/devices/$DIDA/points/$PID/source-priority" -H "$AUTH" -H "$JSON" -d '{"sourcePriority":200}' | data_of "['sourcePriority']")"
check "当前值回到 B 的 9.0" "9.0" "$(curl -s "$BASE/points/$PID/latest" -H "$AUTH" | data_of "['latest']['defo_mm']")"

section "④ 权威来源**没有数据**时回退，而不是给空值"
P2="P-SRC2-$RUN_ID"
PID2=$(curl -s -X POST "$BASE/points" -H "$AUTH" -H "$JSON" \
       -d "{\"objectId\":1,\"code\":\"$P2\",\"name\":\"多源验收临时测点2\",\"type\":\"POINT_DEFORMATION\",\"enabled\":true}" \
       | data_of "['id']")
curl -s -o /dev/null -X POST "$BASE/devices/$DIDA/points/$PID2" -H "$AUTH"
curl -s -o /dev/null -X POST "$BASE/devices/$DIDB/points/$PID2" -H "$AUTH"
curl -s -o /dev/null -X PUT "$BASE/devices/$DIDA/points/$PID2/source-priority" -H "$AUTH" -H "$JSON" -d '{"sourcePriority":10}'
# 只有 B（默认档）报过数；A 是权威来源但一条数据都没有
ingest_id "src2-b-$RUN_ID" "$P2" "$(TS --minutes -3)" '"defo_mm":2.5' "$DB" >/dev/null
check "回退到有数据的 B（不是空值）" "2.5" "$(curl -s "$BASE/points/$PID2/latest" -H "$AUTH" | data_of "['latest']['defo_mm']")"
check "回退时来源标注为 B" "$DB" "$(curl -s "$BASE/points/$PID2/latest" -H "$AUTH" | data_of "['sourceDeviceCode']")"

section "⑤ 三处判据同口径：单点 latest / 项目批量 latest / 概览最大形变"
SINGLE=$(curl -s "$BASE/points/$PID/latest" -H "$AUTH" | data_of "['latest']['defo_mm']")
BATCH=$(curl -s "$BASE/projects/1/points/latest" -H "$AUTH" | python3 -c "import sys,json;rows=json.load(sys.stdin)['data'];hit=[r for r in rows if r['pointId']==$PID];print(hit[0]['latest']['defo_mm'] if hit else '<批量里没有这个点>')")
check "批量端点与单点一致" "$SINGLE" "$BATCH"
BATCH_MAX=$(curl -s "$BASE/projects/1/points/latest" -H "$AUTH" | python3 -c "import sys,json;rows=json.load(sys.stdin)['data'];vals=[abs(r['latest']['defo_mm']) for r in rows if r.get('latest') and r['latest'].get('defo_mm') is not None];print(round(max(vals),6) if vals else '')")
check "概览最大形变 == 各点当前值的最大绝对值（同一判据）" "$BATCH_MAX" \
  "$(curl -s "$BASE/projects/1/summary" -H "$AUTH" | data_of "['maxDeformationMm']")"

section "⑥ 校验与权限"
check "优先级 0 -> 400（越小越优先，0 无意义）" "400" \
  "$(http_code -X PUT "$BASE/devices/$DIDA/points/$PID/source-priority" -H "$AUTH" -H "$JSON" -d '{"sourcePriority":0}')"
check "优先级 1000 -> 400（上限 999，给默认档留回旋）" "400" \
  "$(http_code -X PUT "$BASE/devices/$DIDA/points/$PID/source-priority" -H "$AUTH" -H "$JSON" -d '{"sourcePriority":1000}')"
check "测点不存在 -> 404" "404" \
  "$(http_code -X PUT "$BASE/devices/$DIDA/points/99999/source-priority" -H "$AUTH" -H "$JSON" -d '{"sourcePriority":10}')"
check "值班员改优先级 -> 403（与绑定/标定/解绑同一权限档）" "403" \
  "$(http_code -X PUT "$BASE/devices/$DIDA/points/$PID/source-priority" -H "$OP_AUTH" -H "$JSON" -d '{"sourcePriority":10}')"
check "改优先级写审计" "true" \
  "$(curl -s "$BASE/audit-logs?pageNum=1&pageSize=50&targetType=Device" -H "$AUTH" | python3 -c "import sys,json;rs=json.load(sys.stdin)['data']['records'];print(str(any(r.get('action')=='设置来源优先级' for r in rs)).lower())")"

section "⑦ 回收临时数据"
for pid in "$PID" "$PID2"; do
  curl -s -o /dev/null -X DELETE "$BASE/devices/$DIDA/points/$pid" -H "$AUTH"
  curl -s -o /dev/null -X DELETE "$BASE/devices/$DIDB/points/$pid" -H "$AUTH"
  curl -s -o /dev/null -X DELETE "$BASE/points/$pid" -H "$AUTH"
done
curl -s -o /dev/null -X DELETE "$BASE/devices/$DIDA" -H "$AUTH"
curl -s -o /dev/null -X DELETE "$BASE/devices/$DIDB" -H "$AUTH"
info "已回收两个临时测点与两台临时雷达"

summary
