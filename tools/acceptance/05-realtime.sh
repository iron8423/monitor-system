#!/bin/bash
# 验收 05：SSE 实时推送（验收脚本第 1、3 条的推送侧）。
#   契约依据：《B侧接口契约_M0》§6（EventSource 带不了 Header -> 走 ?token=）。
#   SSE 是异步的，这里一律「轮询等待事件到达 + 超时」，不用固定 sleep 赌时序。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

TOKEN=$(login)
AUTH="Authorization: Bearer $TOKEN"

OUT=$(mktemp)
SSE_PID=""
cleanup() { [ -n "$SSE_PID" ] && kill "$SSE_PID" 2>/dev/null; rm -f "$OUT"; }
trap cleanup EXIT

# wait_for <事件特征串> [秒]：在 SSE 输出里等到该串出现
wait_for() {
  local pat="$1" secs="${2:-10}" n=0
  while [ "$n" -lt $((secs * 4)) ]; do
    grep -q -- "$pat" "$OUT" && return 0
    sleep 0.25; n=$((n + 1))
  done
  return 1
}

section "① 订阅 /stream?token=（后台）"
curl -sN "$BASE/stream?token=$TOKEN" > "$OUT" 2>&1 &
SSE_PID=$!
sleep 1
if wait_for "event:" 6 || [ -s "$OUT" ]; then
  pass "SSE 连接已建立"
else
  fail "SSE 未在 6s 内建立连接" "输出：$(head -c 200 "$OUT")"
fi

NP="P-RT-$RUN_ID"
PID=$(curl -s -X POST "$BASE/points" -H "$AUTH" -H "$JSON" \
      -d "{\"objectId\":1,\"code\":\"$NP\",\"name\":\"实时验收临时测点\",\"type\":\"POINT_DEFORMATION\",\"enabled\":true}" \
      | data_of "['id']")

section "② 上报正常值 -> 应推 measurement 事件（验收第 1 条）"
ingest2 "rt-$RUN_ID-1" "$NP" "2026-08-27T15:00:00+08:00" 0.19 0.05 >/dev/null
if wait_for "event: *measurement" 10; then pass "收到 measurement 事件"; else fail "未收到 measurement 事件" "$(head -c 300 "$OUT")"; fi
# SSE 的 event 名与 data 在**两行**上（"event:measurement" 换行 "data:{...}"），
# 单行正则跨不过去，所以直接断言 data 行里带业务点号
check_grep "事件载荷带业务点号" "^data:.*$NP" "$OUT"

section "③ 上报超限值 -> 应推 alarm 事件（验收第 3 条）"
ingest_id "rt-$RUN_ID-2" "$NP" "2026-08-27T15:01:00+08:00" '"defo_mm":4.6' >/dev/null
if wait_for "event: *alarm" 10; then pass "收到 alarm 事件"; else fail "未收到 alarm 事件" "$(head -c 300 "$OUT")"; fi
if wait_for '"status":"PENDING"' 10; then pass "新警情状态 PENDING"; else fail "alarm 事件未带 PENDING" "$(grep -A 1 'event: *alarm' "$OUT" | tail -1)"; fi

section "④ 处置 -> 应再推一条 alarm 事件（状态变化即时可见）"
AID=$(curl -s "$BASE/alarms?pointId=$PID&status=PENDING" -H "$AUTH" | python3 -c "
import sys,json
print(max(r['id'] for r in json.load(sys.stdin)['data']['records']))")
curl -s -o /dev/null -X POST "$BASE/alarms/$AID/actions" -H "$AUTH" -H "$JSON" -d '{"action":"confirm","comment":"值班确认"}'
if wait_for '"status":"CONFIRMED"' 10; then pass "处置后推 CONFIRMED"; else fail "未收到 CONFIRMED 事件" "$(tail -c 300 "$OUT")"; fi

section "⑤ 设备最近上报时间回写（A 的在线判定只认该字段，契约 §5）"
# 带显式 receiveTime 上报，断言设备状态里的 lastReportTime 与之逐字一致（+08:00 入参应精确往返）。
# 非 +08 偏移（如设备用 UTC）的往返见 02 的第 ⑨ 节。
RTRECV="2026-08-27T15:05:00+08:00"
ingest_recv "rt-$RUN_ID-3" "$NP" "2026-08-27T15:05:00+08:00" "$RTRECV" '"defo_mm":0.3' >/dev/null
BRT=$(curl -s "$BASE/devices/1/status" -H "$AUTH" | data_of "['lastReportTime']")
check "+08:00 的 receiveTime 精确回写到设备" "$RTRECV" "$BRT"
info "注：该步骤把设备最近上报时间推到了过去（设备因此显示离线），下一条把它拨回当前"
ingest_id "rt-$RUN_ID-4" "$NP" "2026-08-27T15:06:00+08:00" '"defo_mm":0.3' >/dev/null
check "再上报后设备回到 ONLINE" "ONLINE" "$(curl -s "$BASE/devices/1/status" -H "$AUTH" | data_of "['status']")"

section "⑥ SSE 鉴权（契约 §6：仅该路径走 query token）"
check "无 token -> 401" "401" "$(http_code "$BASE/stream")"
check "错 token -> 401" "401" "$(http_code "$BASE/stream?token=garbage")"

check_grep "事件流含 alarm 事件" "event: *alarm" "$OUT"
check_grep "事件流含 measurement 事件" "event: *measurement" "$OUT"

recycle_point "$PID"

summary
