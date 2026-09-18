#!/bin/bash
# 验收 16：严格契约模式（复查清单 P0-4）。
#
#   为什么单独一套件、而且**自带一个后端**：生产编排
#   （docker-compose.production.yml 的 INGEST_STRICT_CONTRACT=true）强制打开严格契约，
#   而这条路径在本套件之前**没有任何自动化覆盖**（`grep -c schemaVersion tools/acceptance/*.sh`
#   曾经是 0）。开发环境与其余 15 个套件都跑在 strict-contract=false 上，于是"接上真设备那天
#   才第一次执行这段代码"是当时的真实风险。
#
#   为什么不让 run-all 的 --fresh 直接开 strict：那会同时改掉另外 15 个套件的运行前提
#   （它们发的报文没有 schemaVersion/sequence，会全部被判 UNSUPPORTED_SCHEMA_VERSION）——
#   那不是"覆盖严格模式"，而是把整套验收改写一遍。所以这里**另起一个空库 + strict=true
#   的进程**（端口 18099，可用 STRICT_PORT 覆盖），跑完就收。
#
#   套件钉的是五条拒收理由码都必须真的被判出来：UNSUPPORTED_SCHEMA_VERSION /
#   INVALID_SEQUENCE / DEVICE_POINT_NOT_BOUND / DEVICE_POINT_NOT_CALIBRATED /
#   POSITION_CALIBRATION_MISMATCH，外加正例（完整报文照收、位置与标定一致照收、
#   容差边界外仍拒收）。没有正例的话，一个"把所有报文都拒掉"的实现也能让反面断言全绿。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
source "$HERE/lib.sh"

PORT="${STRICT_PORT:-18099}"
MVNW_OPTS="${MVNW_OPTS:--o}"
LOG=$(mktemp)
BG_PID=""
STARTED=0

# 只收自己起的那个进程组。**刻意不用 pkill -f**：那是全命令行正则匹配，
# 会把"命令行里带同样字符串"的 shell 一起杀掉（run-all.sh 头注释里记着这个跟头，
# 2026-09-18 又踩了一次——所以这里只按进程组收）。
stop_strict_backend() {
  [ -n "$BG_PID" ] && kill -- "-$BG_PID" 2>/dev/null
  for _ in $(seq 1 40); do
    ss -ltn 2>/dev/null | grep -q ":$PORT " || return 0
    sleep 0.25
  done
  printf '%s端口 %s 在 10s 内未释放，请手工确认残留进程%s\n' "$C_YEL" "$PORT" "$C_OFF" >&2
}
cleanup() { [ "$STARTED" = 1 ] && stop_strict_backend; rm -f "$LOG"; }
trap cleanup EXIT

if ss -ltn 2>/dev/null | grep -q ":$PORT "; then
  printf '%s端口 %s 已被占用：严格契约套件要自己起一个后端，请用 STRICT_PORT=xxxx 换端口%s\n' \
    "$C_RED" "$PORT" "$C_OFF" >&2
  exit 2
fi

printf '%s启动严格契约后端：端口 %s（H2 空库，strict-contract=true）...%s\n' "$C_DIM" "$PORT" "$C_OFF"
( cd "$ROOT/backend" && exec setsid ./mvnw $MVNW_OPTS spring-boot:run \
    -Dspring-boot.run.arguments="--server.port=$PORT --monitor.ingest.strict-contract=true" ) > "$LOG" 2>&1 &
BG_PID=$!
STARTED=1

BASE="http://localhost:$PORT/api/v1"
printf '等待就绪'
READY=0
for _ in $(seq 1 150); do
  if curl -sf "$BASE/health" >/dev/null 2>&1; then READY=1; break; fi
  if ! kill -0 "$BG_PID" 2>/dev/null; then break; fi
  printf '.'; sleep 1
done
printf '\n'
if [ "$READY" != 1 ]; then
  printf '%s严格契约后端未就绪，日志尾部：%s\n' "$C_RED" "$C_OFF" >&2
  tail -30 "$LOG" >&2
  exit 2
fi

# 就绪之后才登录：lib.sh 里的 BASE 在调用时才读，这里换成这个临时实例
TOKEN=$(login)
AUTH="Authorization: Bearer $TOKEN"

# ingest <messageId> <device> <point> <schemaVersion> <sequence> [extra 字段串]
#   extra 形如 ,"position":{"angleDeg":20.7,"distanceM":157.16}
ingest() {
  local mid="$1" dev="$2" pt="$3" ver="$4" seq="$5" extra="${6:-}"
  curl -s -X POST "$BASE/ingest/measurements" -H "$JSON" -H "$KEY" \
    -d "{\"items\":[{\"messageId\":\"$mid\",\"deviceId\":\"$dev\",\"pointCode\":\"$pt\",
         \"schemaVersion\":\"$ver\",\"sequence\":$seq,\"collectTime\":\"$(ts 0)\",
         \"metrics\":{\"defo_mm\":1.25},\"quality\":\"VALID\",\"signal\":0.9,\"state\":\"normal\"$extra}]}"
}

# 第一条 result 的 <status>/<reason>；解析失败时给出显式标记（不能静默成空串）
reason_of() {
  local py='import sys,json; d=json.load(sys.stdin).get("data") or {}; rs=d.get("results") or [{}]; print("%s/%s" % (rs[0].get("status") or "?", rs[0].get("reason") or ""))'
  python3 -c "$py" 2>/dev/null || printf '<解析失败>'
}
accepted_of() {
  local py='import sys,json; print((json.load(sys.stdin).get("data") or {}).get("accepted"))'
  python3 -c "$py" 2>/dev/null || printf '<解析失败>'
}
points_n() {
  local py='import sys,json; print(len((json.load(sys.stdin).get("data") or {}).get("points") or []))'
  python3 -c "$py" 2>/dev/null || printf '<解析失败>'
}

section "⓪ 夹具：临时设备 + 项目 1 下的临时测点 + 绑定（刻意不标定）"
DEV="dev-strict-$RUN_ID"
NP="P-STRICT-$RUN_ID"
DID=$(curl -s -X POST "$BASE/devices" -H "$AUTH" -H "$JSON" \
      -d "{\"code\":\"$DEV\",\"name\":\"严格契约验收临时雷达\",\"type\":\"MILLIMETER_WAVE_RADAR\",
           \"longitude\":113.0500000,\"latitude\":23.7200000,\"altitude\":60.000,
           \"antennaHeightM\":10.000,\"headingDegrees\":0,\"pitchDegrees\":0,
           \"detectionRangeM\":265.000,\"halfAngleDegrees\":30.0000,\"verticalHalfAngleDegrees\":15.0000,
           \"battery\":90.0,\"status\":\"ONLINE\"}" | data_of "['id']")
PID=$(curl -s -X POST "$BASE/points" -H "$AUTH" -H "$JSON" \
      -d "{\"objectId\":1,\"code\":\"$NP\",\"name\":\"严格契约验收临时测点\",\"type\":\"POINT_DEFORMATION\",\"enabled\":true}" \
      | data_of "['id']")
check "设备与测点绑定成功" "200" "$(http_code -X POST "$BASE/devices/$DID/points/$PID" -H "$AUTH")"
# 严格模式会核对测项档案（UNKNOWN_METRIC），临时测点必须自己建测项——
# 这是"平台档案与设备上报的测项集合必须一致"的正面要求，不是夹具的将就。
MID=$(curl -s -X POST "$BASE/metrics" -H "$AUTH" -H "$JSON" \
      -d "{\"pointId\":$PID,\"code\":\"defo_mm\",\"name\":\"累计形变\",\"unit\":\"mm\",\"sortOrder\":1}" \
      | data_of "['id']")
info "deviceId=$DID（$DEV，绑定后仍是 PENDING 未标定）/ pointId=$PID（$NP）/ metricId=$MID"

section "① 正例：完整报文照收（种子标定 radar-001 → P-HK02）"
# P-HK02 是 V11 种下的 ACTIVE + 视线通过标定；schemaVersion/sequence 齐全，不带 position。
R=$(ingest "strict-$RUN_ID-ok" radar-001 P-HK02 1.0 1)
check "正例 accepted=1" "1" "$(printf '%s' "$R" | accepted_of)"
check "正例 rejected=0" "0" "$(printf '%s' "$R" | data_of "['rejected']")"

section "② UNSUPPORTED_SCHEMA_VERSION"
check "schemaVersion=0.9 -> 拒收且原因码正确" \
  "REJECTED/UNSUPPORTED_SCHEMA_VERSION" "$(ingest "strict-$RUN_ID-ver" radar-001 P-HK02 0.9 1 | reason_of)"

section "③ INVALID_SEQUENCE"
check "sequence=-1 -> 拒收且原因码正确" \
  "REJECTED/INVALID_SEQUENCE" "$(ingest "strict-$RUN_ID-seq" radar-001 P-HK02 1.0 -1 | reason_of)"

section "④ DEVICE_POINT_NOT_BOUND"
# radar-002 是种子设备，但它只绑了 P-HK01 / P-BP03 / P-BP04，没绑 P-HK02
check "设备未绑定该测点 -> 拒收且原因码正确" \
  "REJECTED/DEVICE_POINT_NOT_BOUND" "$(ingest "strict-$RUN_ID-bind" radar-002 P-HK02 1.0 1 | reason_of)"

section "⑤ DEVICE_POINT_NOT_CALIBRATED"
# 临时绑定刚建好、calibration_status=PENDING：绑定存在但没标定，与上一节是两件事
check "已绑定但从未标定 -> 拒收且原因码正确" \
  "REJECTED/DEVICE_POINT_NOT_CALIBRATED" "$(ingest "strict-$RUN_ID-cal" "$DEV" "$NP" 1.0 1 | reason_of)"

section "⑥ POSITION_CALIBRATION_MISMATCH：位置不符拒收、位置一致照收、容差边界仍拒"
# 标定值**从接口读回来**再原样报出去（不写死数字）：写死会在下一次"设备挪位 + 重新标定"
# 的迁移（V11/V18/V19 都为这件事重建过 device_point）之后静默过期，
# 而那时的症状是"正例失败"，看起来像位置校验坏了。
BIND=$(curl -s "$BASE/devices/1/points" -H "$AUTH")
AZI=$(printf '%s' "$BIND" | python3 -c '
import sys, json
rows = json.load(sys.stdin)["data"]
row = [r for r in rows if r["pointId"] == 2][0]
print(row["azimuthDegrees"])
' 2>/dev/null)
RANGE=$(printf '%s' "$BIND" | python3 -c '
import sys, json
rows = json.load(sys.stdin)["data"]
row = [r for r in rows if r["pointId"] == 2][0]
print(row["slantRangeM"])
' 2>/dev/null)
HEAD=$(curl -s "$BASE/devices/1" -H "$AUTH" | python3 -c '
import sys, json
print(json.load(sys.stdin)["data"]["headingDegrees"])
' 2>/dev/null)
REL=$(python3 -c '
import sys
a, h = float(sys.argv[1]), float(sys.argv[2])
print(round((a - h + 540.0) % 360.0 - 180.0, 3))
' "$AZI" "$HEAD" 2>/dev/null)
REL3=$(python3 -c 'import sys; print(round(float(sys.argv[1]) + 3.0, 3))' "$REL" 2>/dev/null)
[ -n "$REL" ] || { printf '%s读不到 radar-001 → P-HK02 的标定（azimuth/heading），夹具不成立%s\n' \
  "$C_RED" "$C_OFF" >&2; exit 2; }
info "读回的标定：方位 $AZI° / 航向 $HEAD° → 相对角 $REL°、斜距 $RANGE m"

check "报来相对角 90°（远超 2° 容差）-> 拒收且原因码正确" \
  "REJECTED/POSITION_CALIBRATION_MISMATCH" \
  "$(ingest "strict-$RUN_ID-pos" radar-001 P-HK02 1.0 2 \
      ",\"position\":{\"angleDeg\":90.0,\"distanceM\":$RANGE}" | reason_of)"
# 反过拟合：把标定值原样报回来必须照收，否则"位置校验"可以退化成"拒绝一切带位置的报文"
R2=$(ingest "strict-$RUN_ID-posok" radar-001 P-HK02 1.0 3 \
     ",\"position\":{\"angleDeg\":$REL,\"distanceM\":$RANGE}")
check "位置与标定一致 -> accepted=1" "1" "$(printf '%s' "$R2" | accepted_of)"
# 容差边界：相对角偏 3°（容差 2°）必须仍被拒——容差被悄悄放大到 5° 时这一条会当场变红
check "相对角偏 3°（容差 2°）-> 仍拒收" "REJECTED/POSITION_CALIBRATION_MISMATCH" \
  "$(ingest "strict-$RUN_ID-pos3" radar-001 P-HK02 1.0 4 \
      ",\"position\":{\"angleDeg\":$REL3,\"distanceM\":$RANGE}" | reason_of)"

section "⑦ 拒收不落库：P-HK02 上应当只有两条正例落下的 2 个点"
# 拒收 = 整条不入库。这一条防的是"先落库、再校验、失败只回一个码"。
# 两条正例的 collectTime 都是"现在"，会被 P0-3 的默认窗口（近 24 小时）罩住；
# 六条被拒的报文如果也落了库，这里就会多出来。
check "曲线点数" "2" "$(curl -s "$BASE/points/2/series?metricCode=defo_mm" -H "$AUTH" | points_n)"

section "⑧ 临时数据回收"
curl -s -o /dev/null -X DELETE "$BASE/devices/$DID/points/$PID" -H "$AUTH"
[ -n "$MID" ] && curl -s -o /dev/null -X DELETE "$BASE/metrics/$MID" -H "$AUTH"
curl -s -o /dev/null -X DELETE "$BASE/points/$PID" -H "$AUTH"
curl -s -o /dev/null -X DELETE "$BASE/devices/$DID" -H "$AUTH"
info "已回收临时设备/测点（本实例是空库、跑完即销毁；回收只为保持「跑完不留自建数据」的习惯）"

summary
