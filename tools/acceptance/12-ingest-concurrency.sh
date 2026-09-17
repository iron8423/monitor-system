#!/bin/bash
# 验收 12：并发重复上报（清单第 11 条）。
#
#   ① 并发重发同一条消息 -> **一条撞车的消息不该拖垮同批其它完全合法的消息**，且没有 5xx
#   ② 顺序重发的语义没被改动        -> 仍按 DUPLICATE 处理（回归）
#   ③ 整批全是重复消息              -> 全部 DUPLICATE，不写一行、不报错
#
# 缺陷本体（2026-09-17 复现）：ingest 整批一个事务，逐条 INSERT。
# 按 messageIds 的**预取**只挡得住顺序重发；两个并发请求会同时通过预取、各自 INSERT，
# 其中之一撞上 uk_measurement_device_msg。撞了之后 PG 把整个事务置为 aborted，
# 于是**整批回滚**——同批里跟重复毫无关系的合法测点一起丢，客户端拿到 500 而不是 DUPLICATE。
#
# **本套件有没有牙齿**（同一段探针，改动前后各真跑一次，2026-09-17）：
#
#   批内容 = [独有a, 共享(6 个请求相同), 独有b]，N=6 个请求并发
#
#   库 / 实现                                状态码          落库
#   PG  改动前（无保存点）                   1×200 + 5×500   2 行
#   PG  改动后（按消息设保存点）             6×200           13 行
#   H2  改动后（run-all --fresh 的形态）     6×200           13 行
#
# 判据是「改动前会红」，不是「改动后是绿的」——所以第一行才是牙齿。第二行那 5 个 500
# 同时证明了「这台机器上 6 个后台 curl 确实会重叠」，并发是真的、不是碰巧串行跑完。
#
# **但要当心一个同形的假绿**：断言全绿也可能来自「请求被串行化 → 预取挡住了重复」，
# 那条路径与保存点无关，改动前后都会绿。这两种情况在**响应上完全同形**，无法从 HTTP 分辨。
# 所以后端在保存点这条路（唯一索引拦下 → 回查命中）上留了一行 info：
#     grep -c "接入并发重复上报：唯一索引拦下后回查命中" <后端日志>   # 期望 = N-1 = 5
# 实测 H2 上正好 5 次，即 5 个失败者**全部**走的是保存点，不是预取。
# 换机器/换负载后若这个数是 0，套件仍会全绿，但那一刻它没在验这条改动——用这行日志确认。
#
# 与 02-ingest-idempotency.sh 的分工：02 验的是幂等的**功能**语义（同 messageId 重发不重复写），
# 全是顺序请求；本套件只验并发下的不变式——「一条重复上报的杀伤半径只有它自己」。
#
# 前提：`INGEST_STRICT_CONTRACT=false`。本套件用的是临时测点，它没有设备—测点标定，
# 严格契约下第一条消息就会被按「未建档/未标定」拒收，全套断言失去意义。
#   - `run-all.sh --fresh`（H2，spring-boot:run）：application.yml 默认 false ✓
#   - dev/prod compose：默认 true ✗ —— 对容器跑要显式 `INGEST_STRICT_CONTRACT=false`
#     （与其它造临时测点的套件同一个前提，见 README）。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

TOKEN=$(login)
AUTH="Authorization: Bearer $TOKEN"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# 并发度，见文件头。上限 9：下面用「分钟数」给每条消息分配互不相同的 collectTime，
# 超过 9 会让分钟变成两位数、区间对不上。
N=6

# 每个并发请求把状态码写进 $TMP/code<i>，响应体写进 $TMP/body<i>。
count_codes() { grep -hE "^($1)$" "$TMP"/code* 2>/dev/null | wc -l | tr -d ' \n'; }

# 从某个响应体里取 data 下的字段；读不出来打印 <读不出>，让断言给出可读的失败信息。
field_of() {
  python3 -c "
import json,sys
try:
    print(json.load(open('$1'))['data']['$2'])
except Exception:
    print('<读不出>')
"
}

# 数 $TMP/body* 里有多少个满足条件的响应：count_bodies <python 布尔表达式，d 为 data>
count_bodies() {
  python3 - "$TMP" "$1" <<'PY'
import sys, json, glob, os
n = 0
for f in glob.glob(os.path.join(sys.argv[1], "body*")):
    try:
        d = json.load(open(f))["data"]
        if eval(sys.argv[2], {"__builtins__": {}}, {"d": d}):
            n += 1
    except Exception:
        pass
print(n)
PY
}

NP="P-DUPC-$RUN_ID"
PID=$(curl -s -X POST "$BASE/points" -H "$AUTH" -H "$JSON" \
      -d "{\"objectId\":1,\"code\":\"$NP\",\"name\":\"并发重复上报验收临时测点\",\"type\":\"POINT_DEFORMATION\",\"enabled\":true}" \
      | data_of "['id']")
section "⓪ 临时测点 $NP -> pointId=$PID"

SHARED="dupconc-$RUN_ID-shared"
# 该测点在本套件时间窗内的落库行数——本套件的核心观测量。
# granularity 不传（默认 raw）时 series 一行一个点，所以 points 的长度就是行数。
# 窗口按 collect_time 过滤（MeasurementQueryService.series），下面这些 collectTime 全落在这里面。
rows_in_window() {
  curl -s "$BASE/points/$PID/series?from=2026-08-27T13:59:00%2B08:00&to=2026-08-27T14:30:00%2B08:00&metricCode=defo_mm" \
    -H "$AUTH" | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']['points']))"
}

# 只数共享那条消息自己的那一分钟。上面给每条消息分了互不相同的分钟：
# 共享在 14:00，独有 a 在 14:01～14:06，独有 b 在 14:11～14:16，
# 所以这个 59 秒的窗口里**除了共享那条不该有任何东西**——总行数 13 是「加起来对」，
# 这一条是「共享那条自己恰好 1 行」，两者缺一不可：
# 万一它落成 2 行、同时某条独有消息丢了 1 行，总数仍是 13。
shared_rows() {
  curl -s "$BASE/points/$PID/series?from=2026-08-27T14:00:00%2B08:00&to=2026-08-27T14:00:59%2B08:00&metricCode=defo_mm" \
    -H "$AUTH" | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']['points']))"
}

# ---------- ① 并发重发：撞车只该判这一条重复 ----------
section "① $N 个并发请求，每批 = [独有a, 共享, 独有b]（批内 3 条消息）"
rm -f "$TMP"/code* "$TMP"/body*

# 共享那条在 6 个请求里**逐字相同**，必然有一方撞唯一键；
# 两条独有消息则每个请求各不相同、完全合法——它们能不能活下来就是本条的判据。
# 共享消息排在**中间**是刻意的：前面那条独有消息的插入必须挺过保存点回滚，
# 后面那条独有消息必须在撞车之后照常被处理，两头都覆盖到。
for i in $(seq 1 $N); do
  (
    curl -s -o "$TMP/body$i" -w '%{http_code}\n' -X POST "$BASE/ingest/measurements" \
      -H "$JSON" -H "$KEY" \
      -d "{\"items\":[
        {\"messageId\":\"dupconc-$RUN_ID-a$i\",\"deviceId\":\"radar-001\",\"pointCode\":\"$NP\",
         \"collectTime\":\"2026-08-27T14:0$i:00+08:00\",\"metrics\":{\"defo_mm\":1.1},\"quality\":\"VALID\"},
        {\"messageId\":\"$SHARED\",\"deviceId\":\"radar-001\",\"pointCode\":\"$NP\",
         \"collectTime\":\"2026-08-27T14:00:00+08:00\",\"metrics\":{\"defo_mm\":1.2},\"quality\":\"VALID\"},
        {\"messageId\":\"dupconc-$RUN_ID-b$i\",\"deviceId\":\"radar-001\",\"pointCode\":\"$NP\",
         \"collectTime\":\"2026-08-27T14:1$i:00+08:00\",\"metrics\":{\"defo_mm\":1.3},\"quality\":\"VALID\"}]}" \
      > "$TMP/code$i"
  ) &
done
wait

check "全部 $N 个请求都是 200" "$N" "$(count_codes '200')"
# 这一条就是第 11 条的核心：改动前这里是 5。
check "没有任何 5xx" "0" "$(count_codes '5[0-9][0-9]')"
# 恰好一个请求完整落库（它先到，共享那条没撞车）；其余 $((N-1)) 个各自：
# 共享那条判 DUPLICATE，本批另外两条照常落库 -> accepted=2 / duplicates=1。
check "恰好 1 个请求完整落库（accepted=3, duplicates=0）" "1" \
      "$(count_bodies "d['accepted']==3 and d['duplicates']==0")"
check "其余 $((N - 1)) 个请求各自落了 2 条、判了 1 条重复" "$((N - 1))" \
      "$(count_bodies "d['accepted']==2 and d['duplicates']==1")"
# $N 条独有a + 1 条共享 + $N 条独有b。改动前这里是 2。
check "落库行数恰好 $((2 * N + 1)) 行（独有消息一条都不能少）" "$((2 * N + 1))" "$(rows_in_window)"
check "共享那条消息在库里只落了 1 行" "1" "$(shared_rows)"

# ---------- ② 顺序重发：语义没被改动 ----------
section "② 顺序重发（回归：预取路径不该受保存点影响）"
rm -f "$TMP"/code* "$TMP"/body*
BEFORE=$(rows_in_window)
R=$(curl -s -X POST "$BASE/ingest/measurements" -H "$JSON" -H "$KEY" \
    -d "{\"items\":[{\"messageId\":\"$SHARED\",\"deviceId\":\"radar-001\",\"pointCode\":\"$NP\",
         \"collectTime\":\"2026-08-27T14:00:00+08:00\",\"metrics\":{\"defo_mm\":1.2},\"quality\":\"VALID\"}]}")
check "重发 accepted=0" "0" "$(printf '%s' "$R" | data_of "['accepted']")"
check "重发 duplicates=1" "1" "$(printf '%s' "$R" | data_of "['duplicates']")"
check "结果条目 status=DUPLICATE" "DUPLICATE" "$(printf '%s' "$R" | python3 -c "
import sys,json;print(json.load(sys.stdin)['data']['results'][0]['status'])")"
check "重发没有多写一行" "$BEFORE" "$(rows_in_window)"

# ---------- ③ 整批全是重复 ----------
section "③ 整批 3 条消息全是已存在的 -> 全部 DUPLICATE，不写一行、不报错"
rm -f "$TMP"/code* "$TMP"/body*
CODE=$(curl -s -o "$TMP/body1" -w '%{http_code}' -X POST "$BASE/ingest/measurements" \
    -H "$JSON" -H "$KEY" \
    -d "{\"items\":[
      {\"messageId\":\"$SHARED\",\"deviceId\":\"radar-001\",\"pointCode\":\"$NP\",
       \"collectTime\":\"2026-08-27T14:00:00+08:00\",\"metrics\":{\"defo_mm\":1.2},\"quality\":\"VALID\"},
      {\"messageId\":\"dupconc-$RUN_ID-a1\",\"deviceId\":\"radar-001\",\"pointCode\":\"$NP\",
       \"collectTime\":\"2026-08-27T14:01:00+08:00\",\"metrics\":{\"defo_mm\":1.1},\"quality\":\"VALID\"},
      {\"messageId\":\"dupconc-$RUN_ID-b1\",\"deviceId\":\"radar-001\",\"pointCode\":\"$NP\",
       \"collectTime\":\"2026-08-27T14:11:00+08:00\",\"metrics\":{\"defo_mm\":1.3},\"quality\":\"VALID\"}]}")
check "整批重复也返回 200（不是 5xx）" "200" "$CODE"
check "accepted=0" "0" "$(field_of "$TMP/body1" accepted)"
check "duplicates=3（按消息计）" "3" "$(field_of "$TMP/body1" duplicates)"
check "仍然没有多写行" "$BEFORE" "$(rows_in_window)"

recycle_point "$PID" "临时测点 $NP"

section "④ 鉴权"
check "接入无密钥 -> 401" "401" "$(http_code -X POST "$BASE/ingest/measurements" -H "$JSON" \
  -d '{"items":[]}')"

summary
