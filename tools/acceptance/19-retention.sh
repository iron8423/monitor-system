#!/bin/bash
# 验收 19：测量值保留任务（复查清单 P1-1）。
#
#   为什么要自带一个后端（端口 18103，RETENTION_PORT 可换）：保留任务默认**关闭**
#   （删数据不可逆，默认打开意味着任何一次本地启动都可能真删），而这里要验的恰恰是
#   "开了之后删得对不对"。用 --monitor.retention.enabled=true 起一个专属实例，跑完即收
#   ——与 16-strict-contract 同一个套路。
#
#   钉四件事：
#     ① 开关与参数可见（/ops/config 与 /ops/retention 口径一致）；
#     ② 窗口外的行**真被删**，窗口内的新数据保留；
#     ③ 只删 measurement：同一点的警情记录原封不动；
#     ④ 每轮统计可读（deleted / cutoff / batches / durationMs），不是"删完没声"。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
source "$HERE/lib.sh"

PORT="${RETENTION_PORT:-18103}"
MVNW_OPTS="${MVNW_OPTS:--o}"
LOG=$(mktemp)
BG_PID=""
STARTED=0

stop_backend() {
  [ -n "$BG_PID" ] && kill -- "-$BG_PID" 2>/dev/null
  for _ in $(seq 1 40); do
    ss -ltn 2>/dev/null | grep -q ":$PORT " || return 0
    sleep 0.25
  done
  printf '%s端口 %s 在 10s 内未释放，请手工确认残留进程%s\n' "$C_YEL" "$PORT" "$C_OFF" >&2
}
cleanup() { [ "$STARTED" = 1 ] && stop_backend; rm -f "$LOG"; }
trap cleanup EXIT

if ss -ltn 2>/dev/null | grep -q ":$PORT "; then
  printf '%s端口 %s 已被占用：保留任务套件要自己起一个后端，请用 RETENTION_PORT=xxxx 换端口%s\n' \
    "$C_RED" "$PORT" "$C_OFF" >&2
  exit 2
fi

printf '%s启动保留任务后端：端口 %s（H2 空库，enabled=true / raw-days=30 / sweep=2s）...%s\n' \
  "$C_DIM" "$PORT" "$C_OFF"
( cd "$ROOT/backend" && exec setsid ./mvnw $MVNW_OPTS spring-boot:run \
    -Dspring-boot.run.arguments="--server.port=$PORT --monitor.retention.enabled=true --monitor.retention.raw-days=30 --monitor.retention.sweep-ms=2000 --monitor.retention.initial-delay-ms=1000 --monitor.retention.batch-size=100 --monitor.retention.batch-pause-ms=0" ) > "$LOG" 2>&1 &
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
  printf '%s保留任务后端未就绪，日志尾部：%s\n' "$C_RED" "$C_OFF" >&2
  tail -30 "$LOG" >&2
  exit 2
fi

TOKEN=$(login)
AUTH="Authorization: Bearer $TOKEN"
# 时间戳统一走 support/ts_offset.py（bash 里塞多行 python 已经踩过两次）
TS() { python3 "$HERE/support/ts_offset.py" "$@"; }

section "① 开关与参数可见"
CFG=$(curl -s "$BASE/ops/config" -H "$AUTH")
check "enabled=true（本实例显式打开）" "True" \
  "$(printf '%s' "$CFG" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['retention']['enabled'])")"
check "rawDays=30（与启动参数一致）" "30" \
  "$(printf '%s' "$CFG" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['retention']['rawDays'])")"
RET=$(curl -s "$BASE/ops/retention" -H "$AUTH")
check "/ops/retention 与 /ops/config 口径一致" "30" "$(printf '%s' "$RET" | data_of "['rawDays']")"
check_contains "口径说明写明只删测量值" "警情 / 影像 / 审计不动" "$(printf '%s' "$RET" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['note'])")"

section "② 造数：一条窗口外（40 天前）+ 一条窗口内（现在）"
NP="P-RET-$RUN_ID"
PID=$(curl -s -X POST "$BASE/points" -H "$AUTH" -H "$JSON" \
      -d "{\"objectId\":1,\"code\":\"$NP\",\"name\":\"保留任务验收临时测点\",\"type\":\"POINT_DEFORMATION\",\"enabled\":true}" \
      | data_of "['id']")
OLD_CT=$(TS --days -40)
NOW_CT=$(TS)
info "pointId=$PID；老数据 collectTime=$OLD_CT"

# 老数据取 5.0（越过 +5.0 那条种子规则）——顺带造出一条警情，用来验「保留不动警情」。
# **回执要留下**：本套件的实例 sweep-ms=2s、initial-delay-ms=1s，老数据插进去可能
# 在同一个 2 秒窗口里就被扫掉了。所以"它曾经写进去过"必须由**接入回执**来证明，
# 而不是由"插入后还能查得到"来证明——后者是一次真实的竞态（本地跑过 1 次红）。
OLD_ACK=$(ingest_id "ret-old-$RUN_ID" "$NP" "$OLD_CT" '"defo_mm":5.0')
NEW_ACK=$(ingest_id "ret-new-$RUN_ID" "$NP" "$NOW_CT" '"defo_mm":1.0')

OLD_WIN="from=$(TS --days -45 --urlencode)&to=$(TS --days -35 --urlencode)"
NEW_WIN="from=$(TS --hours -2 --urlencode)"
points_in() {
  curl -s "$BASE/points/$PID/series?$1&granularity=raw" -H "$AUTH" \
    | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']['points']))"
}
accepted_of() { python3 -c "import sys,json;print(json.load(sys.stdin)['data']['accepted'])"; }
check "老数据确实写进去了（接入回执 accepted=1）" "1" "$(printf '%s' "$OLD_ACK" | accepted_of)"
check "新数据确实写进去了（接入回执 accepted=1）" "1" "$(printf '%s' "$NEW_ACK" | accepted_of)"
# 窗口内的那条只能被"保留任务误伤"才会消失，所以这一条是确定性的；
# 窗口外那条此刻在不在取决于扫描是否刚好跑过（见上面的回执说明），不在这里断言。
check "造数后：窗口内有 1 条新数据" "1" "$(points_in "$NEW_WIN")"

section "③ 等一轮扫描（最长 30s）：窗口外的被删、窗口内的保留"
DEL=""
for _ in $(seq 1 30); do
  RET=$(curl -s "$BASE/ops/retention" -H "$AUTH")
  DEL=$(printf '%s' "$RET" | python3 -c "import sys,json;d=json.load(sys.stdin)['data'].get('lastRun') or {};print(d.get('deleted',''))")
  [ -n "$DEL" ] && [ "$DEL" != "0" ] && break
  sleep 1
done
check "保留任务至少删掉了 1 行（lastRun.deleted）" "true" \
  "$([ -n "$DEL" ] && [ "$DEL" != "0" ] && echo true || echo false)"
# 两条一起看才成立：回执证明它写进去过（②），这里证明它现在没了（③）。
check "窗口外的老数据已被删除" "0" "$(points_in "$OLD_WIN")"
check "窗口内的新数据仍在" "1" "$(points_in "$NEW_WIN")"
check "统计里给出了截止时间（cutoff）" "true" \
  "$(printf '%s' "$RET" | python3 -c "import sys,json;print(str(bool(json.load(sys.stdin)['data']['lastRun'].get('cutoff'))).lower())")"
check "统计里给出了批次与耗时" "true" \
  "$(printf '%s' "$RET" | python3 -c "import sys,json;d=json.load(sys.stdin)['data']['lastRun'];print(str(isinstance(d.get('batches'),int) and isinstance(d.get('durationMs'),int)).lower())")"

section "④ 只删测量值：同一点的警情记录必须原封不动"
check "该点仍有警情（40 天前那条数据曾被判超限）" "true" \
  "$(curl -s "$BASE/alarms?pointId=$PID" -H "$AUTH" | python3 -c "import sys,json;print(str(json.load(sys.stdin)['data']['total'] > 0).lower())")"
check "审计日志仍然可读（保留任务不碰审计）" "200" "$(http_code "$BASE/audit-logs?pageNum=1&pageSize=5" -H "$AUTH")"

section "⑤ 回收临时数据"
curl -s -o /dev/null -X DELETE "$BASE/points/$PID" -H "$AUTH"
info "已回收临时测点（本实例是空库、跑完即销毁）"

summary
