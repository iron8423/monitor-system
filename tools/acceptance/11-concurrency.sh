#!/bin/bash
# 验收 11：并发一致性（清单第 32 条）。
#
#   ① 并发上报同一测点同一测项的超限值 -> **至多一条**未解除警情，且没有 5xx
#   ② 并发处置同一条警情           -> 恰好一个成功，其余 4xx（**不是 500**），终态唯一、留痕不重复
#   ③ 手工处置与引擎自动解除竞争    -> 终态留痕恰好一条（不能出现「两条留痕、状态只反映一条」）
#
# 为什么需要一个独立的套件：前面十个套件全是**顺序**请求，而第 32 条的缺陷
# （AlarmEngine.findOpen 是 check-then-act）只在真并发下出现。顺序跑一万遍都是绿的——
# 这正是它能活到现在的原因。所以这里刻意用后台 curl + wait 造真并发。
#
# 并发度取 6 而不是更大：每个接入线程在自己的事务里握着 1 条连接，进入告警写入路径的那个
# 再向 REQUIRES_NEW 要第 2 条。Hikari 默认池 10，6+1=7 留有余量；并发度调到接近池上限时，
# 失败原因会从「并发缺陷」变成「连接池不够」，套件就失去判别力了。
#
# **本套件有没有牙齿**（2026-09-16 实测；条数已按 2026-09-17 空库整套真跑复核，逐套件 11→18）：
#
#   改动                                          结果
#   只去掉 KeyLock（进程内锁）                    仍全绿 18/18
#     （旧记录写 17/17，是补上「拿得到刚触发的警情 id」、并把 trigger 断言改成
#      跨该测点全部警情计数之前那一版的条数；本版共 18 处断言点
#      = 16 个无条件 check() + ②③ 各一处 pass|fail）
#   去掉 KeyLock **且** openKey 不再写（=V14 唯一
#     索引形同不存在，等价于改动前的实现）        ① 红 2 条：「未解除警情恰好 1 条」实得 4、
#                                                 「全部警情的 trigger 合计」实得 4
#   只把 AlarmService.act 的 CAS 改回无条件写入   ② 红 5 条：6 个处置全 200、
#                                                 时间线 7 条、终态卡在 PENDING
#
# 第一行是反直觉但重要的一行：**这个套件测的不是「进程内锁在不在」**，而是「不变式成不成立」。
# 单实例下 V14 的唯一索引自己就够用，进程内锁的作用是让并发线程不必靠撞索引失败来收场
# （少一轮异常与回滚，也保住败者那次评估不被丢弃）——它是纵深防御的一层，不是唯一的一层。
# 所以「把锁去掉套件仍然绿」不是套件没牙齿，而是它忠于自己的断言：只测结果，不测实现。
# 反过来说，**只验「加了锁之后是绿的」等于什么都没验**——上面第二、三行才是这条改动被验过的证据。
# 改回去再跑一次绿，才能确认那两次红是改动造成的，而不是套件本身不稳。
#
# 与 04-alarm.sh 的分工：04 验的是告警的**功能**语义（触发/升级/恢复/留痕/防刷屏），
# 本套件只验并发下的**不变式**，不重复 04 的断言。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

TOKEN=$(login)
AUTH="Authorization: Bearer $TOKEN"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

N=6   # 并发度，见文件头

# 每个并发请求把状态码写进 $TMP/code<i>（每行一个），响应体写进 $TMP/body<i>。
# 分开存是为了失败时既能看到「几个 500」也能看到「响应体说了什么」。
# count_codes <扩展正则>：匹配到的请求个数
count_codes() { grep -hE "^($1)$" "$TMP"/code* 2>/dev/null | wc -l | tr -d ' \n'; }

# reset_slots：清掉上一节的状态码/响应体文件。
# 必须每节都清——三节复用的是同一批 code1..codeN 文件名，不清的话上一节的残留会被
# 下一节的 count_codes 数进去（第三节只写 code1/code2，却会把第二节留下的 code3..code6 一起算）。
reset_slots() { rm -f "$TMP"/code* "$TMP"/body*; }

# sum_accepted：把全部并发响应体里的 data.accepted 加起来（读不出来的按 0 计）
sum_accepted() {
  python3 - "$TMP" <<'PY'
import sys, json, glob, os
total = 0
for f in glob.glob(os.path.join(sys.argv[1], "body*")):
    try:
        total += json.load(open(f))["data"]["accepted"]
    except Exception:
        pass
print(total)
PY
}

NP="P-CONC-$RUN_ID"
PID=$(curl -s -X POST "$BASE/points" -H "$AUTH" -H "$JSON" \
      -d "{\"objectId\":1,\"code\":\"$NP\",\"name\":\"并发一致性验收临时测点\",\"type\":\"POINT_DEFORMATION\",\"enabled\":true}" \
      | data_of "['id']")
section "⓪ 临时测点 $NP -> pointId=$PID"

# 该点该测项未解除警情数（本套件的核心观测量）
open_count() { curl -s "$BASE/alarms?pointId=$PID&status=PENDING" -H "$AUTH" | data_of "['total']"; }

# ---------- ① 并发触发 ----------
section "① $N 个并发上报（同一测点同一测项，全部越过 gte +3.0）"
reset_slots

# messageId 必须各不相同：相同的会被幂等键挡掉、只有 1 条真正落库，
# 于是「只有一条警情」就成了幂等的功劳而不是并发控制的功劳，套件失去判别力。
# 第 11 条（并发重复上报）是另一回事，由 02 套件与后续批次覆盖。
for i in $(seq 1 $N); do
  (
    curl -s -o "$TMP/body$i" -w '%{http_code}\n' -X POST "$BASE/ingest/measurements" \
      -H "$JSON" -H "$KEY" \
      -d "{\"items\":[{\"messageId\":\"conc-$RUN_ID-$i\",\"deviceId\":\"radar-001\",\"pointCode\":\"$NP\",
           \"collectTime\":\"2026-08-27T14:00:0$i+08:00\",\"metrics\":{\"defo_mm\":4.2},
           \"quality\":\"VALID\",\"position\":{\"angleDeg\":49.0,\"distanceM\":20.1},
           \"signal\":0.9,\"state\":\"normal\"}]}" > "$TMP/code$i"
  ) &
done
wait

check "全部 $N 个请求都是 200" "$N" "$(count_codes '200')"
check "没有任何 5xx（并发不该让接入报错）" "0" "$(count_codes '5[0-9][0-9]')"
check "全部 $N 条都真实落库（accepted 合计）" "$N" "$(sum_accepted)"
# 这一条就是第 32 条的核心。旧实现下每个并发线程 findOpen 都读到「没有未解除警情」，
# 各自 INSERT，这里会是 2~N。
check "未解除警情恰好 1 条（不是 N 条）" "1" "$(open_count)"

# trigger 留痕要**跨该测点的全部警情**数，不能只看 id 最大的那一条。
# 这不是洁癖：出现 4 条重复警情时，只看最新那条的话它的时间线里恰好也只有 1 条 trigger，
# 断言照样绿——上面那条「未解除警情恰好 1 条」才是红的那个。把总数数进来，两条断言各测一层，
# 且这条在「重复警情被建出来之后又被谁关掉了」这种更绕的失败模式下也能报出来。
AIDS=$(curl -s "$BASE/alarms?pointId=$PID" -H "$AUTH" | python3 -c "
import sys,json;print(' '.join(str(r['id']) for r in json.load(sys.stdin)['data']['records']))")
AID=$(echo "$AIDS" | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -n | tail -1)
# lib.sh 里没有 is_id（那是 10-scope.sh 自己定义的、且返回的是字符串不是退出码），
# 用同一套写法就地判一下：拿不到 id 时后面的断言会连片失败，先把原因单列一条。
check "拿得到刚触发的警情 id" "true" \
      "$(case "$AID" in ''|*[!0-9]*) echo false ;; *) echo true ;; esac)"
TRIGGERS=0
for aid in $AIDS; do
  n=$(curl -s "$BASE/alarms/$aid" -H "$AUTH" | python3 -c "
import sys,json;print(sum(1 for t in json.load(sys.stdin)['data']['timeline'] if t['action']=='trigger'))")
  TRIGGERS=$((TRIGGERS + n))
done
check "该测点全部警情的 trigger 留痕合计恰好 1 条" "1" "$TRIGGERS"

# ---------- ② 并发处置同一警情 ----------
section "② $N 个并发处置同一条警情（全部 resolve）"
reset_slots

# 为什么期望「恰好一个 200」而不是「全部 200」：处置是状态机流转，
# 已到终态的警情不再接受处置。两个处置人同时打开同一条待确认警情是值班的常态，
# 旧实现下两个人都能提交成功，时间线上留下两条互相矛盾的处置记录。
for i in $(seq 1 $N); do
  (
    curl -s -o "$TMP/body$i" -w '%{http_code}\n' -X POST "$BASE/alarms/$AID/actions" \
      -H "$AUTH" -H "$JSON" -d '{"action":"resolve","comment":"并发处置验收"}' > "$TMP/code$i"
  ) &
done
wait

check "恰好 1 个处置成功" "1" "$(count_codes '200')"
check "没有任何 5xx" "0" "$(count_codes '5[0-9][0-9]')"
check "其余请求都是 4xx（400 已终态 / 409 并发冲突）" "$((N - 1))" "$(count_codes '4[0-9][0-9]')"
# 409 是 CAS 落空（读到 PENDING 后写入时已被别人改掉），400 是读到时就已终态。
# 两者都正确，**取决于请求到达的先后**——所以这一条只作为观测值打印，不做断言。
#
# 为什么降级（2026-09-18 由 CI 抓出来）：这里原本要求「至少一个 409」。那条断言测的其实是
# 6 个 curl 有没有在同一毫秒级窗口里交错；在负载高的机器上，赢家先提交、其余全部读到终态，
# 结果清一色 400，断言随机变红——它测的是调度，不是不变式。
# 真正要钉的语义（「带过期状态的 CAS 会写 0 行」）由确定性单测覆盖：
# `AlarmConcurrencySemanticsTest#casWithStaleStatusWritesNothing`（H2 上持锁 3 秒构造过期条件）。
# 本节剩下的硬断言仍然覆盖真正的并发不变式：恰好 1 个成功、无 5xx、其余 4xx、终态唯一、
# 时间线恰好 2 条。
CTS=$(count_codes '409')
info "状态码分布：$(grep -h . "$TMP"/code* | sort | uniq -c | tr '\n' ' ')（409 = 走 CAS 落空路径：$CTS 个；0 个表示本轮没有交错，不是缺陷）"

check "终态为 RESOLVED" "RESOLVED" "$(curl -s "$BASE/alarms/$AID" -H "$AUTH" | data_of "['status']")"
# 触发 1 条 + 成功的处置 1 条 = 2。多于 2 就是有并发请求覆盖了状态还照样留痕。
TL=$(curl -s "$BASE/alarms/$AID" -H "$AUTH" | python3 -c "
import sys,json;print(len(json.load(sys.stdin)['data']['timeline']))")
check "时间线共 2 条（触发 + 唯一一次成功处置）" "2" "$TL"

# ---------- ③ 手工处置 vs 引擎自动解除 ----------
section "③ 手工 resolve 与引擎自动解除同时发生 -> 终态留痕恰好一条"
reset_slots

# 重新造一条未解除警情（①② 那条已经在终态了；同点同规则还在抑制窗口内，
# 所以换一个测点，避免与防刷屏的语义纠缠）
CP="P-RACE-$RUN_ID"
CPID=$(curl -s -X POST "$BASE/points" -H "$AUTH" -H "$JSON" \
       -d "{\"objectId\":1,\"code\":\"$CP\",\"name\":\"并发竞争验收临时测点\",\"type\":\"POINT_DEFORMATION\",\"enabled\":true}" \
       | data_of "['id']")
ingest_id "race-$RUN_ID-1" "$CP" "2026-08-27T15:00:00+08:00" '"defo_mm":4.2' >/dev/null
RID=$(curl -s "$BASE/alarms?pointId=$CPID&status=PENDING" -H "$AUTH" | python3 -c "
import sys,json;print(max(r['id'] for r in json.load(sys.stdin)['data']['records']))")
check "先开出一条未解除警情" "1" "$(curl -s "$BASE/alarms?pointId=$CPID&status=PENDING" -H "$AUTH" | data_of "['total']")"

# 同时：人工 resolve 它，以及上报一个回落到恢复值以内（<= +1.0）的值让引擎自动解除。
# 谁先谁后不确定，但**两条路径都以 status=PENDING 为前置条件**，所以只能有一条生效。
( curl -s -o "$TMP/body1" -w '%{http_code}\n' -X POST "$BASE/alarms/$RID/actions" \
    -H "$AUTH" -H "$JSON" -d '{"action":"resolve","comment":"与自动解除竞争"}' > "$TMP/code1" ) &
( curl -s -o "$TMP/body2" -w '%{http_code}\n' -X POST "$BASE/ingest/measurements" \
    -H "$JSON" -H "$KEY" \
    -d "{\"items\":[{\"messageId\":\"race-$RUN_ID-2\",\"deviceId\":\"radar-001\",\"pointCode\":\"$CP\",
         \"collectTime\":\"2026-08-27T15:05:00+08:00\",\"metrics\":{\"defo_mm\":0.5},
         \"quality\":\"VALID\"}]}" > "$TMP/code2" ) &
wait

check "两个请求都不是 5xx" "0" "$(count_codes '5[0-9][0-9]')"
check "该点已无未解除警情" "0" "$(curl -s "$BASE/alarms?pointId=$CPID&status=PENDING" -H "$AUTH" | data_of "['total']")"
check "终态为 RESOLVED" "RESOLVED" "$(curl -s "$BASE/alarms/$RID" -H "$AUTH" | data_of "['status']")"
TERMINAL=$(curl -s "$BASE/alarms/$RID" -H "$AUTH" | python3 -c "
import sys,json
print(sum(1 for t in json.load(sys.stdin)['data']['timeline'] if t['action']=='recover'))")
# 人工 resolve 记的是 resolve、引擎自动解除记的是 recover。两者都以 PENDING 为前提，
# 所以「recover 恰好 1 条」等价于「引擎的那次 CAS 成功了」；如果人工先赢，
# 引擎的 CAS 会落空、recover 为 0——那也是正确的。这里断言的是**不超过 1**，
# 因为出问题的情况恰恰是「两条都写进去了」。
if [ "$TERMINAL" -le 1 ]; then
  pass "引擎侧 recover 留痕不超过 1 条（实际 $TERMINAL 条；0 表示人工先赢，同样正确）"
else
  fail "引擎侧 recover 留痕出现 $TERMINAL 条" "两条解除路径都写入了留痕，说明 CAS 没拦住"
fi

recycle_point "$PID" "临时测点 $NP"
recycle_point "$CPID" "临时测点 $CP"

section "④ 鉴权"
check "告警列表无 JWT -> 401" "401" "$(http_code "$BASE/alarms")"

summary
