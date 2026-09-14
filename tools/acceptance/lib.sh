#!/bin/bash
# 验收脚本公共库：登录 / 上报 / 断言 / 汇总。
#
# 用法：脚本顶部 `source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"`。
#
# 可覆盖的环境变量：
#   BASE        后端基址    （默认 http://localhost:8080/api/v1）
#   INGEST_KEY  接入共享密钥（默认 dev-ingest-key）
#   ADMIN_USER  登录账号    （默认 admin）
#   ADMIN_PASS  登录口令    （默认 123456）
#   RUN_ID      本次运行标识（默认 时间戳-PID；决定临时消息号与临时测点号）

BASE="${BASE:-http://localhost:8080/api/v1}"
INGEST_KEY="${INGEST_KEY:-dev-ingest-key}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASS="${ADMIN_PASS:-123456}"

JSON='Content-Type: application/json'
KEY="X-Ingest-Key: $INGEST_KEY"

# 每次运行唯一：消息号带它 → 脚本可重复执行而不会撞幂等键（撞了会变成 duplicates，
# 断言随之失败，但那是「测出上一次的残留」而不是「脚本不能重跑」）。
RUN_ID="${RUN_ID:-$(date +%s)-$$}"

PASS_N=0
FAIL_N=0

if [ -t 1 ]; then C_RED=$'\033[31m'; C_GRN=$'\033[32m'; C_DIM=$'\033[2m'; C_OFF=$'\033[0m'
else C_RED=; C_GRN=; C_DIM=; C_OFF=; fi

section() { printf '\n%s---- %s ----%s\n' "$C_DIM" "$1" "$C_OFF"; }
info()    { printf '  %s\n' "$1"; }
pass()    { PASS_N=$((PASS_N + 1)); printf '  %s✓%s %s\n' "$C_GRN" "$C_OFF" "$1"; }
fail()    { FAIL_N=$((FAIL_N + 1)); printf '  %s✗%s %s\n' "$C_RED" "$C_OFF" "$1"
            [ -n "$2" ] && printf '      %s\n' "$2"; return 0; }

# check <描述> <期望> <实得>
check() { if [ "$2" = "$3" ]; then pass "$1"; else fail "$1" "期望 [$2] 实得 [$3]"; fi; }

# check_contains <描述> <应包含子串> <整串>
#   注意：这里走的是 shell 通配（glob），`*` 是通配符、空格是字面量。
#   要按正则匹配请用下面的 check_grep，别把正则塞进来。
check_contains() {
  case "$3" in *"$2"*) pass "$1" ;; *) fail "$1" "期望包含 [$2]，实得 [$3]" ;; esac
}

# check_grep <描述> <扩展正则> <文件>
check_grep() {
  if grep -qE -- "$2" "$3" 2>/dev/null; then pass "$1"; else fail "$1" "在 $3 中未匹配到正则 /$2/"; fi
}

# http_code <curl 参数...> -> 只输出 HTTP 状态码
http_code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

# data_of <对 data 的下标表达式>：从 stdin 读 {"code","data"}，取 data 下的值
#   用法：curl -s ... | data_of "['total']"
data_of() {
  python3 -c "
import sys, json
try:
    print(json.load(sys.stdin)['data']$1)
except Exception:
    print('<非 JSON 或结构不符>')
"
}

# code_of：从 stdin 读信封，只取业务码 code（0 表示成功）
code_of() {
  python3 -c "
import sys, json
try:
    print(json.load(sys.stdin)['code'])
except Exception:
    print('<非 JSON>')
"
}

# login_as <用户名> [口令] -> 打印该账号的 JWT（登录失败打印空串，不退出）。
# 角色相关的用例要用非管理员账号（operator / analyst / maintainer），见 V2 种子数据。
login_as() {
  curl -s -X POST "$BASE/auth/login" -H "$JSON" \
    -d "{\"username\":\"${1:-$ADMIN_USER}\",\"password\":\"${2:-$ADMIN_PASS}\"}" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])" 2>/dev/null
}

# login -> 打印 JWT；后端不可达或账号不对时直接退出（退出码 2，区别于断言失败）
login() {
  local tok
  tok=$(login_as "$ADMIN_USER" "$ADMIN_PASS")
  if [ -z "$tok" ]; then
    printf '%s后端不可达或登录失败：%s（账号 %s）%s\n' "$C_RED" "$BASE" "$ADMIN_USER" "$C_OFF" >&2
    printf '先确认后端已启动：cd backend && ./mvnw spring-boot:run\n' >&2
    exit 2
  fi
  printf '%s' "$tok"
}

# 以下三个上报函数**一律 messageId 打头**，避免参数错位。
# （曾经有过一个自动生成 messageId 的同名 ingest，与 ingest_id 签名不同，
#   结果被按 ingest_id 的签名调用，messageId 被当成点号——整条请求变成垃圾。）

#   ingest_id <messageId> <pointCode> <collectTime> <metrics JSON 片段> [deviceId]
#   例：ingest_id "k1-$RUN_ID" P-HK01 2026-08-27T12:00:00+08:00 '"defo_mm":4.2'
ingest_id() {
  local mid="$1" point="$2" ct="$3" metrics="$4" dev="${5:-radar-001}"
  curl -s -X POST "$BASE/ingest/measurements" -H "$JSON" -H "$KEY" \
    -d "{\"items\":[{\"messageId\":\"$mid\",\"deviceId\":\"$dev\",\"pointCode\":\"$point\",\"collectTime\":\"$ct\",\"metrics\":{$metrics},\"quality\":\"VALID\",\"position\":{\"angleDeg\":49.0,\"distanceM\":20.1},\"signal\":0.9,\"state\":\"normal\"}]}"
}

# 带 receiveTime 上报（用于验证设备最近上报时间回写这一条链路）。
#   ingest_recv <messageId> <pointCode> <collectTime> <receiveTime> <metrics JSON 片段>
ingest_recv() {
  local mid="$1" point="$2" ct="$3" rt="$4" metrics="$5"
  curl -s -X POST "$BASE/ingest/measurements" -H "$JSON" -H "$KEY" \
    -d "{\"items\":[{\"messageId\":\"$mid\",\"deviceId\":\"radar-001\",\"pointCode\":\"$point\",\"collectTime\":\"$ct\",\"receiveTime\":\"$rt\",\"metrics\":{$metrics},\"quality\":\"VALID\"}]}"
}

# 上报一条含 defo_mm + rate_mm_d 双测项的消息（契约 D1：每点 2 测项 → 拆 2 行）。
ingest2() {
  local mid="$1" point="$2" ct="$3" defo="$4" rate="$5" dev="${6:-radar-001}"
  curl -s -X POST "$BASE/ingest/measurements" -H "$JSON" -H "$KEY" \
    -d "{\"items\":[{\"messageId\":\"$mid\",\"deviceId\":\"$dev\",\"pointCode\":\"$point\",\"collectTime\":\"$ct\",\"metrics\":{\"defo_mm\":$defo,\"rate_mm_d\":$rate},\"quality\":\"VALID\",\"position\":{\"angleDeg\":49.0,\"distanceM\":20.1},\"signal\":0.9,\"state\":\"normal\"}]}"
}

# recycle_point <pointId> [说明]
#
#   回收临时测点：**先结掉它上面未解除的警情，再删点**。顺序不能反。
#
#   为什么必须这个顺序：`monitor_point` 有 `deleted` 逻辑删除列，`alarm` 表却**没有**
#   「测点被删则警情失效」这回事——警情只记 `point_id`，不做联表校验。点一软删，
#   那条警情就成了「指向一个看不见的测点的孤儿」，而且**照样出现在告警中心的待办队列里**，
#   处置时点进去也画不出曲线（点已经不在 `/points` 里了）。
#
#   历次跑验收攒下了 20 条这样的孤儿（18 条待确认），把演示库的值班工作台整个占满——
#   演示时打开告警中心，满屏都是查不到测点的历史垃圾。这不是某一次跑的问题，是**每跑一轮就多几条**。
#
#   为什么在套件侧回收而不是让后端过滤：后端按「点是否已删」隐藏警情会改掉契约语义
#   （警情是历史事实，不该因为档案变动而消失）。用户已定案：产品行为不动，回收责任归造数方。
recycle_point() {
  local pid="$1" note="${2:-临时测点}"
  [ -n "$pid" ] || return 0

  local ids n=0 id code
  # 一次拉够：只取非终态（CLOSED = RESOLVED / FALSE_ALARM，同 AlarmConstants）
  ids=$(curl -s "$BASE/alarms?pointId=$pid&pageNum=1&pageSize=200" -H "$AUTH" | python3 -c "
import sys, json
try:
    rs = json.load(sys.stdin)['data']['records']
except Exception:
    print(''); raise SystemExit
print(' '.join(str(r['id']) for r in rs if r['status'] not in ('RESOLVED', 'FALSE_ALARM')))")

  for id in $ids; do
    curl -s -o /dev/null -X POST "$BASE/alarms/$id/actions" -H "$AUTH" -H "$JSON" \
      -d '{"action":"resolve","comment":"验收套件回收：临时测点即将删除"}' && n=$((n + 1))
  done
  [ "$n" -gt 0 ] && info "已结掉 $note 上的 $n 条未解除警情（避免留下孤儿）"

  code=$(http_code -X DELETE "$BASE/points/$pid" -H "$AUTH")
  if [ "$code" = "200" ]; then info "已回收$note"; else info "$note 未回收（HTTP $code），可忽略"; fi
}

# recycle_device <deviceId> [说明]
#
#   回收临时设备：**先结掉它上面未解除的警情，再删设备**。理由与 recycle_point 完全一样，
#   只是挂在设备侧——`device` 也是逻辑删除，而 `alarm` 同样不做联表校验，
#   点一删那些 `DEVICE` 类警情就成了孤儿：列表里还在排队，`deviceCode` 却解析成 null，
#   告警中心会多出一行「空白设备」的待办。
#
#   为什么 07 套件没用它也没留孤儿：那套件的设备告警在 ③ 被**恢复上报**正常解除掉了，
#   走到删除时已经没有未解除警情。但那是那一条用例的巧合，不是通用保证——
#   凡是「造出设备告警又不还原」的套件都得走这里（09-data-quality.sh 就是）。
recycle_device() {
  local did="$1" note="${2:-临时设备}"
  [ -n "$did" ] || return 0

  local ids n=0 id
  ids=$(curl -s "$BASE/alarms?deviceId=$did&pageNum=1&pageSize=200" -H "$AUTH" | python3 -c "
import sys, json
try:
    rs = json.load(sys.stdin)['data']['records']
except Exception:
    print(''); raise SystemExit
print(' '.join(str(r['id']) for r in rs if r['status'] not in ('RESOLVED', 'FALSE_ALARM')))")

  for id in $ids; do
    curl -s -o /dev/null -X POST "$BASE/alarms/$id/actions" -H "$AUTH" -H "$JSON" \
      -d '{"action":"resolve","comment":"验收套件回收：临时设备即将删除"}' && n=$((n + 1))
  done
  [ "$n" -gt 0 ] && info "已结掉 $note 上的 $n 条未解除警情（避免留下孤儿）"

  local code
  code=$(http_code -X DELETE "$BASE/devices/$did" -H "$AUTH")
  if [ "$code" = "200" ]; then info "已回收$note"; else info "$note 未回收（HTTP $code），可忽略"; fi
}

# 脚本收口：打印计数、输出机器可读行供 run-all 汇总、设置退出码。
summary() {
  local name; name="$(basename "$0")"
  printf '\n%s---- %s: 通过 %d / 失败 %d ----%s\n' "$C_DIM" "$name" "$PASS_N" "$FAIL_N" "$C_OFF"
  printf '#RESULT pass=%d fail=%d\n' "$PASS_N" "$FAIL_N"
  if [ "$FAIL_N" -eq 0 ]; then exit 0; else exit 1; fi
}
