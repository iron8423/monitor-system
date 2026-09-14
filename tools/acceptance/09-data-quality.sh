#!/bin/bash
# 验收 09：数据可信度告警（验收第 5 条的扩展面）—— 数据质量异常 / 数据延迟上报。
#
#   与 07（设备离线告警）同构：都是定时扫描类的告警，都不是从 HTTP 请求直接产生的，
#   所以同样**一律轮询等待，不赌固定 sleep**。
#   扫描间隔默认 10s（monitor.data-quality.sweep-ms）；run-all.sh --fresh 会调成 2s。
#
#   本套件真正的价值不在「能开出一条警情」，而在下面三条**容易写错**的口径：
#
#   ① **两个成因互不掩盖**（④）。V7 之前 `alarm_type = DEVICE` 只有离线一种成因，
#      于是 DeviceAlarmMonitor 用「该设备有没有未解除的 DEVICE 告警」当「已经告过警了」
#      的判据——加上数据质量/延迟之后这个等价关系断了。不按成因查的话，
#      一台「数据不可信」的设备永远不会被判离线（会以为已经告过警了），反之亦然，
#      两个监视器互相屏蔽、谁都开不出警情。④ 就是钉这条：两种成因同时存在，两条警情都在。
#      这与 B-14（设备告警整类漏算）是同一类错误——拿更宽的条件当更窄条件的判据。
#
#   ② **设备不新鲜时已有警情不能被悄悄销掉**（③）。窗口按 receive_time 取，
#      设备一掉线窗口就空了。若把「窗口不再满足判据」当成恢复，就会把一条**从未恢复**的
#      数据质量警情自动解除——看上去是「自愈」，实际是丢事件。故判据只在设备**在报数**时生效，
#      判不了就原样不动。
#
#   ③ **回补历史数据不算延迟上报**（⑤）。`radar_csv_replay` 回放的是旧 CSV、
#      03 套件为画曲线也会写过去时刻的点，它们的 receive−collect 天然是几天几十天。
#      延迟判据因此额外要求 collectTime 本身也落在窗口内：「延迟」= 这条数据是**刚采的**
#      却过了很久才到。⑤ 直接用一条 collectTime 在窗口外的老报文证明它不触发。
#
#   时间口径：collectTime 一律带 +08:00 显式偏移（Times.parse 会归一到 Asia/Shanghai），
#   receiveTime **不传**、由后端取自己的 now()。两边各自与后端的「现在」同源，
#   所以本套件不依赖跑它的机器在哪个时区。若把 JVM 时区挪出 Asia/Shanghai，
#   延迟那几条会**显式转红**而不是静默通过（后端镜像已 `ENV TZ=Asia/Shanghai` 钉死）。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

TOKEN=$(login)
AUTH="Authorization: Bearer $TOKEN"

# ts <分钟偏移>：相对**现在**的 ISO8601 带 +08:00 偏移（正数=未来，负数=过去）。
#   走 python3 而不是 `date -d`：后者是 GNU 扩展，macOS 上写法完全不同，
#   而 python3 已是本套件的硬依赖（data_of 就在用）。
ts() {
  python3 -c "
import datetime, sys
z = datetime.timezone(datetime.timedelta(hours=8))
print((datetime.datetime.now(z) + datetime.timedelta(minutes=float(sys.argv[1]))).isoformat(timespec='seconds'))" "$1"
}

# ingest_q <messageId> <设备码> <quality> <collectTime> <defo_mm>
#   receiveTime 刻意不传：让它等于后端自己的 now()，与扫描器取的 now() 同源。
ingest_q() {
  curl -s -X POST "$BASE/ingest/measurements" -H "$JSON" -H "$KEY" \
    -d "{\"items\":[{\"messageId\":\"$1\",\"deviceId\":\"$2\",\"pointCode\":\"$NP\",\"collectTime\":\"$4\",\"metrics\":{\"defo_mm\":$5},\"quality\":\"$3\"}]}"
}

# flood <设备码> <quality> <条数> <collectTime> <标签>
#
#   标签（第 5 个参数）**每次调用必须不同**，它进 messageId。
#   幂等键是 device_id + message_id + metric_code：两轮灌数若用了同一批 messageId，
#   第二轮会**整批**被判为 duplicates、一条都不落库（上报接口照样返回 200，不报错）。
#   症状是「判据明明该成立、警情却没开」（或反过来该解除没解除），
#   看上去像后端缺陷，实际是套件根本没造出数据——本套件第一版就是这么红的。
#   因此 FLOOD_ACCEPTED 必须由调用方断言：它是这类错误唯一会喊出声的地方。
FLOOD_ACCEPTED=0
flood() {
  local dev="$1" q="$2" n="$3" ct="$4" tag="$5" i
  FLOOD_ACCEPTED=0
  # 一次只发一条，所以信封里的 accepted 非 0 即 1：1 = 落库了，0 = 被判重复。
  # 读汇总而不是看 HTTP 码——重复上报**是 200**，不看这个字段根本发现不了。
  for i in $(seq 1 "$n"); do
    if [ "$(ingest_q "$RUN_ID-$dev-$tag-$i" "$dev" "$q" "$ct" "0.2" | data_of "['accepted']")" = "1" ]; then
      FLOOD_ACCEPTED=$((FLOOD_ACCEPTED + 1))
    fi
  done
}

# pend_ids <设备码> -> 该设备当前未解除的设备告警，形如 "12:DATA_QUALITY 13:OFFLINE"
pend_ids() {
  curl -s "$BASE/alarms?deviceId=$1&alarmType=DEVICE&pageSize=200" -H "$AUTH" | python3 -c "
import sys, json
try:
    rs = json.load(sys.stdin)['data']['records']
except Exception:
    print(''); raise SystemExit
rows = sorted('%s:%s' % (r['id'], r.get('alarmReason'))
              for r in rs if r['status'] not in ('RESOLVED', 'FALSE_ALARM'))
print(' '.join(rows))"
}

# alarm_of <设备id> <成因> -> 该成因下未解除警情的 id（没有则空串）
alarm_of() {
  curl -s "$BASE/alarms?deviceId=$1&alarmType=DEVICE&pageSize=200" -H "$AUTH" | python3 -c "
import sys, json
try:
    rs = json.load(sys.stdin)['data']['records']
except Exception:
    print(''); raise SystemExit
print(max((r['id'] for r in rs
           if r['status'] not in ('RESOLVED', 'FALSE_ALARM') and r.get('alarmReason') == '$2'),
          default=''))"
}

# 等到该成因下出现未解除警情（最多 40s），回显 id
wait_alarm() {
  local id=''
  for _ in $(seq 1 80); do
    id=$(alarm_of "$1" "$2")
    [ -n "$id" ] && break
    sleep 0.5
  done
  printf '%s' "$id"
}

# 等到该警情不再是 PENDING（即被自动解除）
wait_resolved() {
  for _ in $(seq 1 80); do
    [ "$(curl -s "$BASE/alarms/$1" -H "$AUTH" | data_of "['status']")" = "RESOLVED" ] && return 0
    sleep 0.5
  done
  return 1
}

# ---------- 夹具 ----------
# 自带 project→scene→object→point 四级 + 两台设备：质量与延迟**各用一台**。
#   为什么必须分开：两个判据共用同一个窗口（都是「最近 15 分钟收到的行」）。
#   若在同一台设备上先灌 5 条坏质量、再灌 5 条延迟，延迟占比会被前面那 5 条好数据稀释到
#   5/10 = 50%、够不到 60% 的阈值——断言就会以一种很难看懂的方式失败。
#   一台设备一件事，套件里也更好读。
PRJ=$(curl -s -X POST "$BASE/projects" -H "$AUTH" -H "$JSON" \
      -d "{\"organizationId\":1,\"name\":\"数据质量验收\",\"code\":\"PRJ-DQ-$RUN_ID\"}" | data_of "['id']")
SCN=$(curl -s -X POST "$BASE/scenes" -H "$AUTH" -H "$JSON" \
      -d "{\"projectId\":$PRJ,\"name\":\"临时场景\",\"type\":\"SLOPE\"}" | data_of "['id']")
OBJ=$(curl -s -X POST "$BASE/objects" -H "$AUTH" -H "$JSON" \
      -d "{\"sceneId\":$SCN,\"name\":\"临时对象\",\"type\":\"SLOPE_BODY\"}" | data_of "['id']")
NP="P-DQ-$RUN_ID"
PID=$(curl -s -X POST "$BASE/points" -H "$AUTH" -H "$JSON" \
      -d "{\"objectId\":$OBJ,\"code\":\"$NP\",\"name\":\"数据质量验收临时测点\",\"type\":\"POINT_DEFORMATION\",\"enabled\":true}" \
      | data_of "['id']")

DEVQ="dev-dq-$RUN_ID"      # 质量异常
DEVD="dev-delay-$RUN_ID"   # 延迟上报
DQID=$(curl -s -X POST "$BASE/devices" -H "$AUTH" -H "$JSON" \
       -d "{\"code\":\"$DEVQ\",\"name\":\"数据质量验收设备\",\"type\":\"MILLIMETER_WAVE_RADAR\",\"battery\":90.0,\"status\":\"ONLINE\"}" \
       | data_of "['id']")
DDID=$(curl -s -X POST "$BASE/devices" -H "$AUTH" -H "$JSON" \
       -d "{\"code\":\"$DEVD\",\"name\":\"数据延迟验收设备\",\"type\":\"MILLIMETER_WAVE_RADAR\",\"battery\":90.0,\"status\":\"ONLINE\"}" \
       | data_of "['id']")
# 绑到本项目测点上，设备告警才归属到这个项目（summary 的 alertCount 靠 device_point 反查）
for d in "$DQID" "$DDID"; do
  http_code -X POST "$BASE/devices/$d/points/$PID" -H "$AUTH" >/dev/null
done

alert_count() { curl -s "$BASE/projects/$PRJ/summary" -H "$AUTH" | data_of "['alertCount']"; }

section "⓪ 夹具就位：两台设备、各自干净"
info "质量设备 id=$DQID / 延迟设备 id=$DDID / 测点 $NP"
check "两台设备都没有未解除的设备告警" "" "$(pend_ids "$DQID")"
check "项目初始无未解除警情" "0" "$(alert_count)"

section "① 数据质量异常：在报数但数据坏 -> 开警情"
# 先灌够样本量再等扫描：阈值是「窗口内 ≥4 条样本且坏质量占比 ≥60%」（DataQualityPolicy）。
# 灌 5 条全坏 -> 100%，稳稳过线；灌够 5 条而不是刚好 4 条，是留给「窗口边缘被切掉一条」的余量。
NOW=$(ts 0)
flood "$DEVQ" "SUSPECT" 5 "$NOW" q1
check "本批数据确实落库（不是幂等键撞上的重复）" "5" "$FLOOD_ACCEPTED"
check "设备已因上报而被判在线" "ONLINE" \
  "$(curl -s "$BASE/devices/$DQID/status" -H "$AUTH" | data_of "['status']")"

QAID=$(wait_alarm "$DQID" "DATA_QUALITY")
if [ -n "$QAID" ]; then
  pass "扫描到数据质量异常并生成告警 alarmId=$QAID"
else
  fail "40s 内未生成数据质量告警" "扫描间隔见 monitor.data-quality.sweep-ms；判据见 DataQualityPolicy"
fi

Q=$(curl -s "$BASE/alarms/$QAID" -H "$AUTH")
check "来源是设备告警（不新增 alarm_type）" "DEVICE" "$(printf '%s' "$Q" | data_of "['alarmType']")"
check "成因标记为 DATA_QUALITY" "DATA_QUALITY" "$(printf '%s' "$Q" | data_of "['alarmReason']")"
check "挂在设备上" "$DEVQ" "$(printf '%s' "$Q" | data_of "['deviceCode']")"
check "等级取最低档 notice" "notice" "$(printf '%s' "$Q" | data_of "['level']")"
check "状态 PENDING" "PENDING" "$(printf '%s' "$Q" | data_of "['status']")"
check "快照里的成因与落库一致" "DATA_QUALITY" "$(printf '%s' "$Q" | data_of "['snapshot']['reason']")"
check "快照带判定窗口分钟数" "15" "$(printf '%s' "$Q" | data_of "['snapshot']['windowMinutes']")"
check "快照带坏质量占比阈值" "0.6" "$(printf '%s' "$Q" | data_of "['snapshot']['badRatio']")"
check "快照带最小样本数" "4" "$(printf '%s' "$Q" | data_of "['snapshot']['minSamples']")"
check "时间线首条为系统触发" "trigger" "$(printf '%s' "$Q" | python3 -c "
import sys,json;print(json.load(sys.stdin)['data']['timeline'][0]['action'])")"
# 留痕写的是**实际观测到的数**（窗口里几条、坏了几条），不是策略常量：
# 策略会随代码变，历史留痕不该跟着变，所以观测值必须落在时间线的备注里。
#
# 不断言「恰好 5 条」：扫描是 2s 一轮，可能与灌数据并发，赶在中间那轮扫到的话
# 窗口里是 4 条（占比仍 100%，警情照开）。断言能抓住的是「两个数是实际数出来的、
# 且全坏」——这既排除了常量串，也不会因为这点竞态偶发转红。
check "触发备注写的是实际观测（N 条中 N 条全坏）" "True" "$(printf '%s' "$Q" | python3 -c "
import sys, json, re
n = json.load(sys.stdin)['data']['timeline'][0]['comment']
m = re.search(r'(\d+) 条数据中有 (\d+) 条', n)
print(bool(m) and '质量异常' in n and m.group(1) == m.group(2) and int(m.group(1)) >= 4)")"
check "列表接口也带成因（前端靠它区分掉线/数据坏）" "DATA_QUALITY" \
  "$(curl -s "$BASE/alarms?deviceId=$DQID&pageSize=1" -H "$AUTH" | data_of "['records'][0]['alarmReason']")"
check "项目 summary 的 alertCount 含这条" "1" "$(alert_count)"

section "② 数据恢复 -> 警情自动解除"
# 补 6 条 VALID：窗口内变成 5 坏 / 11 总 = 45%，低于 60% -> 判据不成立。
# 不能只补 5 条（5/10 = 50%，虽然也过线但只剩 10 个百分点的余量，太贴边）。
flood "$DEVQ" "VALID" 6 "$(ts 0)" q2
check "本批恢复数据确实落库" "6" "$FLOOD_ACCEPTED"
if wait_resolved "$QAID"; then
  pass "数据恢复后告警自动解除"
else
  fail "40s 内未自动解除" "status=$(curl -s "$BASE/alarms/$QAID" -H "$AUTH" | data_of "['status']")"
fi
check "解除动作记为 recover" "recover" "$(curl -s "$BASE/alarms/$QAID" -H "$AUTH" | python3 -c "
import sys,json;print(json.load(sys.stdin)['data']['timeline'][-1]['action'])")"
check "解除后该设备无未解除警情" "" "$(pend_ids "$DQID")"
# 终态要能减掉：证明计入的是「未解除」而不是「全部」
check "解除后项目 alertCount 归零" "0" "$(alert_count)"

section "③ 判不了的时候不许动已有警情（闸门 + 放开后照常解除）"
# 先重新开出警情：② 留下的 6 条 VALID 还在窗口里，占比要重新越过 60% 才开得出来
#   （5+8=13 坏 / 11+8=19 总 = 68%）。灌 5 条只有 62.5%，贴边、不好读。
flood "$DEVQ" "SUSPECT" 8 "$(ts 0)" q3
check "本批坏数据确实落库（否则占比根本越不过阈值）" "8" "$FLOOD_ACCEPTED"
QAID2=$(wait_alarm "$DQID" "DATA_QUALITY")
if [ -n "$QAID2" ]; then pass "重新开出数据质量警情 alarmId=$QAID2"
else fail "未能重新开出数据质量警情" "上一步的恢复可能没生效"; fi

# 把设备标成 FAULT，**再**补 6 条 VALID 把占比压到 45%（低于 60% 阈值）。
# 此时判据本身是**不成立**的，唯一的拦截者就是 reconcile 开头那道 `if (!judgeable) return;`：
#   没有闸 -> 判成「已恢复」自动解除（看着像自愈，实际是把一条从未恢复的事件丢了）；
#   有闸   -> 原样待着。
#
# 为什么用 FAULT 而不是「掉线」来摆这个前提：掉线要求 last_report_time 变旧，
# 而写数据又必然把它刷成现在——两者互相打架，只能靠「写完立刻把时间改回去」抢在
# 下一轮扫描（2s）之前，断言就会带上一个真实的竞态。FAULT 是人在档案里标的状态，
# 与上报无关，可以确定性地摆好。（两个理由走的是同一行 return，验一个即验另一个；
# 「掉线时警情不被销掉」在 ④ 里另有一条，那条同时还在验两个监视器互不掩盖。）
curl -s -o /dev/null -X PUT "$BASE/devices/$DQID" -H "$AUTH" -H "$JSON" \
  -d "{\"code\":\"$DEVQ\",\"name\":\"数据质量验收设备\",\"type\":\"MILLIMETER_WAVE_RADAR\",\"lastReportTime\":\"$(ts 0)\",\"battery\":90.0,\"status\":\"FAULT\"}"
check "设备已被标为故障（闸门的触发条件就位）" "FAULT" \
  "$(curl -s "$BASE/devices/$DQID/status" -H "$AUTH" | data_of "['status']")"
flood "$DEVQ" "VALID" 6 "$(ts 0)" q4
check "本批恢复数据确实落库（占比已被压到阈值以下）" "6" "$FLOOD_ACCEPTED"
# 等足够多轮扫描（每轮 2s，这里给 10s ≈ 5 轮），期间质量警情必须原样待着
sleep 10
check "判不了时质量警情仍为 PENDING（未被当成恢复销掉）" "PENDING" \
  "$(curl -s "$BASE/alarms/$QAID2" -H "$AUTH" | data_of "['status']")"

# 另一半：把闸门放开（摘掉 FAULT、上报时间刷成现在）。判据依旧不成立，
# 这次应当**正常解除**——证明那道闸是「暂时不判」而不是「永久冻结」。
# 少了这条，一个「永远不解除」的实现也能让上面那条绿。
curl -s -o /dev/null -X PUT "$BASE/devices/$DQID" -H "$AUTH" -H "$JSON" \
  -d "{\"code\":\"$DEVQ\",\"name\":\"数据质量验收设备\",\"type\":\"MILLIMETER_WAVE_RADAR\",\"lastReportTime\":\"$(ts 0)\",\"battery\":90.0,\"status\":\"ONLINE\"}"
check "设备恢复可判状态（ONLINE）" "ONLINE" \
  "$(curl -s "$BASE/devices/$DQID/status" -H "$AUTH" | data_of "['status']")"
if wait_resolved "$QAID2"; then
  pass "闸门放开后，判据不成立即正常解除"
else
  fail "40s 内未解除" "闸门可能变成了永久冻结（判不了就再也不判）"
fi
check "解除后项目 alertCount 归零" "0" "$(alert_count)"

section "④ 两个成因互不掩盖：同一台设备上离线与数据质量各开一条"
# 这是本套件最要紧的一条。先把质量警情重新开出来，再让设备掉线——
# 于是这台设备同时欠着两件事，两条警情都该在：
#   - 若离线扫描按「有没有未解除的 DEVICE 告警」判（V7 之前的写法），它会以为已经告过警了，
#     离线警情**开不出来**（断言「离线成因也被开出来了」就会红）；
#   - 反过来，若质量扫描按同样的宽条件判，掉线设备上就永远开不出质量警情。
# 这与 B-14（设备告警整类漏算）是同一类错误：拿更宽的条件当更窄条件的判据。
#
# 灌 8 条：窗口里已有 13 坏 / 25 总（③ 留下的），要到 (13+N)/(25+N) ≥ 60% 才开得出来，
# 解出来 N ≥ 5；取 8 留出余量，免得读这条断言的人还要自己算边界。
flood "$DEVQ" "SUSPECT" 8 "$(ts 0)" q5
check "本批坏数据确实落库" "8" "$FLOOD_ACCEPTED"
QAID3=$(wait_alarm "$DQID" "DATA_QUALITY")
check "质量警情已就位（下面要验它不被离线警情顶掉）" "True" "$([ -n "$QAID3" ] && echo True || echo False)"
# 把上报时间拨回过去 = 设备掉线；status 留在 ONLINE 让 statusOf 能推出 OFFLINE
curl -s -o /dev/null -X PUT "$BASE/devices/$DQID" -H "$AUTH" -H "$JSON" \
  -d "{\"code\":\"$DEVQ\",\"name\":\"数据质量验收设备\",\"type\":\"MILLIMETER_WAVE_RADAR\",\"lastReportTime\":\"2020-01-01T00:00:00\",\"battery\":90.0,\"status\":\"ONLINE\"}"
check "设备状态接口改判离线" "OFFLINE" \
  "$(curl -s "$BASE/devices/$DQID/status" -H "$AUTH" | data_of "['status']")"
OAID=$(wait_alarm "$DQID" "OFFLINE")
if [ -n "$OAID" ]; then
  pass "离线成因也被开出来了 alarmId=$OAID（没有被质量警情掩盖）"
else
  fail "40s 内未开出离线告警" "两个监视器可能在互相掩盖——检查 openAlarmOf 是否按成因过滤"
fi
check "同一台设备上两种成因并存（共 2 条未解除）" "2" \
  "$(pend_ids "$DQID" | wc -w | tr -d ' ')"
check "掉线时质量警情也还在（两种成因各管各的，谁也没顶掉谁）" "PENDING" \
  "$(curl -s "$BASE/alarms/$QAID3" -H "$AUTH" | data_of "['status']")"
check "两条的成因集合正确" "DATA_QUALITY OFFLINE" \
  "$(pend_ids "$DQID" | tr ' ' '\n' | sed 's/^[0-9]*://' | sort | tr '\n' ' ' | sed 's/ $//')"
check "离线警情的快照仍带 offlineMinutes（未因新增成因而丢字段）" "5" \
  "$(curl -s "$BASE/alarms/$OAID" -H "$AUTH" | data_of "['snapshot']['offlineMinutes']")"
check "项目 alertCount 为 2（两种成因都计入）" "2" "$(alert_count)"

section "⑤ 数据延迟上报：刚采的却迟到 -> 开警情"
# collectTime 取 12 分钟前（落在 15 分钟窗口内），receiveTime 由后端取 now
#   -> 延迟 = 12 分钟 > 阈值 10 分钟。灌 5 条 -> 占比 100%。
flood "$DEVD" "VALID" 5 "$(ts -12)" d1
check "本批延迟数据确实落库" "5" "$FLOOD_ACCEPTED"
DAID=$(wait_alarm "$DDID" "DATA_DELAY")
if [ -n "$DAID" ]; then
  pass "扫描到延迟上报并生成告警 alarmId=$DAID"
else
  fail "40s 内未生成延迟告警" "判据见 DataQualityPolicy#isDelayed"
fi
D=$(curl -s "$BASE/alarms/$DAID" -H "$AUTH")
check "成因标记为 DATA_DELAY" "DATA_DELAY" "$(printf '%s' "$D" | data_of "['alarmReason']")"
check "快照带延迟阈值分钟数" "10" "$(printf '%s' "$D" | data_of "['snapshot']['delayMinutes']")"
check "等级同为 notice" "notice" "$(printf '%s' "$D" | data_of "['level']")"

section "⑥ 回补历史数据**不算**延迟上报（这条口径的边界）"
# 同一台设备再灌 5 条 collectTime 在窗口外的老报文（receive−collect 是 30 天）。
# 它们与 ⑤ 那 5 条一起进窗口，但**延迟占比**只数「collectTime 也在窗口内」的行：
#   5 条延迟 / 10 条总 = 50% < 60% -> 判据不成立，警情应当被解除。
# 若把「延迟」写成只看 receive−collect，这里会算成 10/10 = 100%，警情就会一直挂着不清——
# 而真跑起来受影响的是 radar_csv_replay 与 03 套件造的历史点，届时不但是误报，
# 而且会**永远解不掉**（每来一批历史数据就把它续上）。
flood "$DEVD" "VALID" 5 "$(ts -43200)" d2
check "本批历史数据确实落库（它们只该撑大分母，不该算作延迟）" "5" "$FLOOD_ACCEPTED"
if wait_resolved "$DAID"; then
  pass "窗口外的历史数据不构成延迟，警情自动解除"
else
  fail "40s 内未解除" "延迟判据可能没有卡 collectTime 也落在窗口内"
fi

section "⑦ 鉴权"
check "警情列表无 JWT -> 401" "401" "$(http_code "$BASE/alarms")"
check "上报缺 X-Ingest-Key -> 401" "401" "$(http_code -X POST "$BASE/ingest/measurements" -H "$JSON" \
  -d "{\"items\":[{\"messageId\":\"$RUN_ID-nokey\",\"deviceId\":\"$DEVQ\",\"pointCode\":\"$NP\",\"collectTime\":\"$(ts 0)\",\"metrics\":{\"defo_mm\":0.1}}]}")"

# ---------- 回收 ----------
# 先结警情再删设备（recycle_device 的职责）。本套件在 ④ 之后留着 2 条未解除的设备告警，
# 不结掉就会变成指向已删设备的孤儿，在告警中心里排成一行 blank 待办。
recycle_device "$DQID" "质量验收设备"
recycle_device "$DDID" "延迟验收设备"
recycle_point "$PID" "数据质量验收临时测点"
for r in "objects/$OBJ" "scenes/$SCN" "projects/$PRJ"; do
  C=$(http_code -X DELETE "$BASE/$r" -H "$AUTH")
  [ "$C" = "200" ] || info "临时 $r 未回收（HTTP $C），可忽略"
done
info "已回收临时项目链"

summary
