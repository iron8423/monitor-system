#!/bin/bash
# 验收 07：设备离线告警（验收第 5 条）—— 断连 -> 设备判离线 + 生成设备告警 -> 恢复自动解除。
#
#   为什么单独一套件：设备告警走的是定时扫描（DeviceAlarmMonitor），与 05 的 SSE 链路不是一条；
#   而且它需要一个「很久没上报」的设备，用种子里那台雷达做不出来（05 会把它的上报时间拨到现在）。
#
#   另外它也是**设备告警进入项目概览**那条口径唯一的落脚点：告警两条来源挂的字段不同
#   （POINT 写 point_id、DEVICE 写 device_id），summary 曾只判 point_id 而整类漏算设备告警（B-14）。
#   ①-b / ② / ③ 三条断言把这口径钉住，改 summary 时会红。
#
#   扫描间隔默认 10s（monitor.device-offline.sweep-ms）；run-all.sh --fresh 会调成 2s 加快本套件。
#   这里一律轮询等待，不赌固定 sleep。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

TOKEN=$(login)
AUTH="Authorization: Bearer $TOKEN"

# 夹具自带 project→scene→object→point 四级：⑤ 的 alertCount 断言要的是**这个项目**下的
# 未解除警情数，只有项目下干净（本项目只有这一个测点、一台设备）才能断言绝对条数，
# 不必赌此刻库里别处没有别人的警情在办。同 03-query.sh ⑨ 的做法。
PRJ=$(curl -s -X POST "$BASE/projects" -H "$AUTH" -H "$JSON" \
      -d "{\"organizationId\":1,\"name\":\"设备告警验收\",\"code\":\"PRJ-DEV-$RUN_ID\"}" | data_of "['id']")
SCN=$(curl -s -X POST "$BASE/scenes" -H "$AUTH" -H "$JSON" \
      -d "{\"projectId\":$PRJ,\"name\":\"临时场景\",\"type\":\"SLOPE\"}" | data_of "['id']")
OBJ=$(curl -s -X POST "$BASE/objects" -H "$AUTH" -H "$JSON" \
      -d "{\"sceneId\":$SCN,\"name\":\"临时对象\",\"type\":\"SLOPE_BODY\"}" | data_of "['id']")
NP="P-DEV-$RUN_ID"
PID=$(curl -s -X POST "$BASE/points" -H "$AUTH" -H "$JSON" \
      -d "{\"objectId\":$OBJ,\"code\":\"$NP\",\"name\":\"设备告警验收临时测点\",\"type\":\"POINT_DEFORMATION\",\"enabled\":true}" \
      | data_of "['id']")

# alert_count：该项目当前的「未解除警情」数（= summary 的 alertCount）
alert_count() { curl -s "$BASE/projects/$PRJ/summary" -H "$AUTH" | data_of "['alertCount']"; }

# pend_alarm <deviceId>：该设备当前未解除的设备告警 id（没有则空串）
pend_alarm() {
  curl -s "$BASE/alarms?deviceId=$1&alarmType=DEVICE&status=PENDING" -H "$AUTH" | python3 -c "
import sys,json
rs=json.load(sys.stdin)['data']['records']
print(max((r['id'] for r in rs), default=''))"
}

# status_of <alarmId>
status_of() { curl -s "$BASE/alarms/$1" -H "$AUTH" | data_of "['status']"; }

section "⓪ 建两台设备：一台早已停止上报，一台从未上报过"
# lastReportTime 远早于 5 分钟窗口 -> 判离线（BaseCrudController 把 body 反序列化成实体）
DEV="dev-acc-$RUN_ID"
DID=$(curl -s -X POST "$BASE/devices" -H "$AUTH" -H "$JSON" \
      -d "{\"code\":\"$DEV\",\"name\":\"验收临时设备\",\"type\":\"MILLIMETER_WAVE_RADAR\",\"lastReportTime\":\"2020-01-01T00:00:00\",\"battery\":88.0,\"status\":\"ONLINE\"}" \
      | data_of "['id']")
# 从未上报过：last_report_time 为空，「离线」是它的自然状态，但那不是事件
NEVER="dev-never-$RUN_ID"
DID2=$(curl -s -X POST "$BASE/devices" -H "$AUTH" -H "$JSON" \
       -d "{\"code\":\"$NEVER\",\"name\":\"从未上报的设备\",\"type\":\"MILLIMETER_WAVE_RADAR\"}" \
       | data_of "['id']")
info "deviceId=$DID（停报）/ $DID2（从未上报）"
# 「前置干净」这条**必须在绑定之前**取：绑定一发生，远处那台停报设备就归属到本项目了，
# 而 DeviceAlarmMonitor 每 10 秒扫一次（monitor.device-offline.sweep-ms），
# 它的离线警情随时可能落到项目上——放在绑定之后断言就是在跟这个 10 秒窗口赛跑，
# 本地跑过 1 次红（实得 1 条）。语义没变：验的是"这个项目此刻本来是干净的"。
check "绑定前该项目无未解除警情（设备尚未归属本项目）" "0" "$(alert_count)"
# 把停报设备挂到本项目那个临时测点上——设备告警靠 device_point 反查才归属到项目，
# 不挂的话它对本项目就是「无主」的，summary 数不到它不是缺陷而是对的。
BIND=$(http_code -X POST "$BASE/devices/$DID/points/$PID" -H "$AUTH")
check "设备与测点绑定成功" "200" "$BIND"
check "停报设备的状态接口已判离线" "OFFLINE" \
  "$(curl -s "$BASE/devices/$DID/status" -H "$AUTH" | data_of "['status']")"
check "从未上报的设备同样显示离线" "OFFLINE" \
  "$(curl -s "$BASE/devices/$DID2/status" -H "$AUTH" | data_of "['status']")"

section "① 断连 -> 生成设备告警（验收第 5 条）"
# 扫描是定时的，等价于「设备已经掉了，等引擎发现」。
AID=""
for _ in $(seq 1 80); do
  AID=$(pend_alarm "$DID")
  [ -n "$AID" ] && break
  sleep 0.5
done
if [ -n "$AID" ]; then
  pass "扫描到离线设备并生成告警 alarmId=$AID"
else
  fail "40s 内未生成设备告警" "扫描间隔见 monitor.device-offline.sweep-ms（默认 10s）"
fi

DET=$(curl -s "$BASE/alarms/$AID" -H "$AUTH")
check "来源标记为 DEVICE" "DEVICE" "$(printf '%s' "$DET" | data_of "['alarmType']")"
check "挂在设备上（deviceCode）" "$DEV" "$(printf '%s' "$DET" | data_of "['deviceCode']")"
check "不带测点（设备告警无点可挂）" "True" "$(printf '%s' "$DET" | python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
print(d.get('pointId') is None and d.get('pointCode') is None)")"
check "等级取最低档 notice" "notice" "$(printf '%s' "$DET" | data_of "['level']")"
check "状态 PENDING" "PENDING" "$(printf '%s' "$DET" | data_of "['status']")"
check "快照带离线判据分钟数" "5" "$(printf '%s' "$DET" | data_of "['snapshot']['offlineMinutes']")"
check "时间线首条为系统触发" "trigger" "$(printf '%s' "$DET" | python3 -c "
import sys,json;print(json.load(sys.stdin)['data']['timeline'][0]['action'])")"

section "①-b 设备告警必须计入项目概览的「未解除警情」（回归：曾整类漏算）"
# 两条来源挂的东西不一样：POINT 只写 point_id，DEVICE 只写 device_id（另一侧 NULL）。
# summary 原先只判 point_id IN (...)，而 SQL 里 NULL IN (...) 不成立 -> 设备告警一条也数不进来。
# 这条如果不是 1，说明又退回了只判 point_id 的写法。
check "项目 summary 的 alertCount 含这条设备告警" "1" "$(alert_count)"

section "② 设备告警复用同一套处置管线（同一张表、同一个状态机、同一套留痕）"
check "出现在全局警情列表里" "True" "$(curl -s "$BASE/alarms?pageSize=200" -H "$AUTH" | python3 -c "
import sys,json
print(any(r['id']==$AID for r in json.load(sys.stdin)['data']['records']))")"
# 请求体里的 "operator":"验收员" 是**诱饵**（同 04-alarm.sh）：署名取登录身份，不由调用方自报。
check "confirm -> CONFIRMED" "CONFIRMED" \
  "$(curl -s -X POST "$BASE/alarms/$AID/actions" -H "$AUTH" -H "$JSON" \
     -d '{"action":"confirm","comment":"值班确认设备离线","operator":"验收员"}' | data_of "['status']")"
check "处置留痕操作人取登录身份（请求体里的 operator 被忽略）" "$ADMIN_USER" \
  "$(curl -s "$BASE/alarms/$AID" -H "$AUTH" | python3 -c "
import sys,json;print(json.load(sys.stdin)['data']['timeline'][-1]['operator'])")"
# CONFIRMED 不是终态，「在办」仍要计入——顺带证明纳入设备告警不是无脑全算
check "确认后仍在办（CONFIRMED 未解除）" "1" "$(alert_count)"

section "③ 恢复上报 -> 告警自动解除"
# 设备带这条消息的 deviceId 上报 -> IngestService 回写 last_report_time=now -> 下一轮扫描应解除
ingest_id "dev-$RUN_ID-1" "$NP" "2026-08-27T16:00:00+08:00" '"defo_mm":0.2' "$DEV" >/dev/null
check "设备状态回到 ONLINE" "ONLINE" "$(curl -s "$BASE/devices/$DID/status" -H "$AUTH" | data_of "['status']")"
OK=""
for _ in $(seq 1 80); do
  [ "$(status_of "$AID")" = "RESOLVED" ] && { OK=1; break; }
  sleep 0.5
done
if [ -n "$OK" ]; then pass "告警已自动解除"; else fail "40s 内未自动解除" "status=$(status_of "$AID")"; fi
check "解除动作记为 recover" "recover" "$(curl -s "$BASE/alarms/$AID" -H "$AUTH" | python3 -c "
import sys,json;print(json.load(sys.stdin)['data']['timeline'][-1]['action'])")"
check "解除后该设备无未解除警情" "" "$(pend_alarm "$DID")"
# 终态要能减掉：证明纳入设备告警的是「未解除」而不是「全部设备告警」
check "解除后项目 alertCount 归零" "0" "$(alert_count)"

section "④ 从未上报过的设备不告警（建档不是事件）"
# 上面几轮等待已跨过多次扫描，这里不必再等：不是「还没来得及扫」，是真的不产生告警
check "该设备全程无告警（含已解除）" "0" \
  "$(curl -s "$BASE/alarms?deviceId=$DID2&pageSize=1" -H "$AUTH" | data_of "['total']")"

section "⑤ 过滤与鉴权"
# 过滤前后 total 必须一致：既能证明 alarmType 生效，又不会因其它设备的告警而脆弱
check "alarmType 大小写归一（DEVICE / device 结果一致）" \
  "$(curl -s "$BASE/alarms?alarmType=DEVICE&pageSize=1" -H "$AUTH" | data_of "['total']")" \
  "$(curl -s "$BASE/alarms?alarmType=device&pageSize=1" -H "$AUTH" | data_of "['total']")"
check "按设备筛出的告警都归该设备" "True" \
  "$(curl -s "$BASE/alarms?deviceId=$DID&pageSize=200" -H "$AUTH" | python3 -c "
import sys,json
rs=json.load(sys.stdin)['data']['records']
print(len(rs) > 0 and all(r['deviceId']==$DID for r in rs))")"
check "警情列表无 JWT -> 401" "401" "$(http_code "$BASE/alarms")"

# 先解绑：`device_point` 没有软删列，删设备/删测点都不会动它，那条绑定会**永久**留在库里，
# 指向两个都已逻辑删除的档案——`GET /devices/{id}/points` 的可见性检查只看设备，
# 于是对一个 `GET /devices/{id}` 已经 404 的设备号，这个端点照样回 200 并吐出一条幽灵绑定。
# 解绑必须**在删设备之前**：`unbind` 内部先 `requireDevice`，设备一删它就 404 了。
# 只解 $DID——$DID2 从头到尾没有绑定（见上方夹具）。
curl -s -o /dev/null -X DELETE "$BASE/devices/$DID/points/$PID" -H "$AUTH"

# 走 recycle_device 而不是裸 DELETE：③ 是「恢复上报 → 自动解除」，一旦那一步红了，
# 这台设备上就留着未解除的 DEVICE 警情，裸删设备会让它变成一条指向已删设备的孤儿
# ——告警中心里排出一行 blank 待办。正常路径下 recycle_device 查无未解除警情，是空操作。
for d in "$DID" "$DID2"; do
  recycle_device "$d" "临时设备 $d"
done
# 项目链从叶子往上删（同 03-query.sh）。删测点是逻辑删除，measurement 随之对所有查询不可见。
# 测点必须走 recycle_point：这套件本身就会在这台临时设备上造出设备告警，
# 直接删点会把它变成孤儿留在演示库里（本套件的 ①-b 断言 alertCount 的口径正是「未解除」）。
recycle_point "$PID"
for r in "objects/$OBJ" "scenes/$SCN" "projects/$PRJ"; do
  C=$(http_code -X DELETE "$BASE/$r" -H "$AUTH")
  [ "$C" = "200" ] || info "临时 $r 未回收（HTTP $C），可忽略"
done
info "已回收临时项目链"

summary
