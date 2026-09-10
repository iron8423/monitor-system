#!/bin/bash
# 验收 07：设备离线告警（验收第 5 条）—— 断连 -> 设备判离线 + 生成设备告警 -> 恢复自动解除。
#
#   为什么单独一套件：设备告警走的是定时扫描（DeviceAlarmMonitor），与 05 的 SSE 链路不是一条；
#   而且它需要一个「很久没上报」的设备，用种子里那台雷达做不出来（05 会把它的上报时间拨到现在）。
#
#   扫描间隔默认 10s（monitor.device-offline.sweep-ms）；run-all.sh --fresh 会调成 2s 加快本套件。
#   这里一律轮询等待，不赌固定 sleep。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

TOKEN=$(login)
AUTH="Authorization: Bearer $TOKEN"

NP="P-DEV-$RUN_ID"
PID=$(curl -s -X POST "$BASE/points" -H "$AUTH" -H "$JSON" \
      -d "{\"objectId\":1,\"code\":\"$NP\",\"name\":\"设备告警验收临时测点\",\"type\":\"POINT_DEFORMATION\",\"enabled\":true}" \
      | data_of "['id']")

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

section "② 设备告警复用同一套处置管线（同一张表、同一个状态机、同一套留痕）"
check "出现在全局警情列表里" "True" "$(curl -s "$BASE/alarms?pageSize=200" -H "$AUTH" | python3 -c "
import sys,json
print(any(r['id']==$AID for r in json.load(sys.stdin)['data']['records']))")"
check "confirm -> CONFIRMED" "CONFIRMED" \
  "$(curl -s -X POST "$BASE/alarms/$AID/actions" -H "$AUTH" -H "$JSON" \
     -d '{"action":"confirm","comment":"值班确认设备离线","operator":"验收员"}' | data_of "['status']")"
check "处置留痕操作人" "验收员" "$(curl -s "$BASE/alarms/$AID" -H "$AUTH" | python3 -c "
import sys,json;print(json.load(sys.stdin)['data']['timeline'][-1]['operator'])")"

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

for d in "$DID" "$DID2"; do
  DEL=$(http_code -X DELETE "$BASE/devices/$d" -H "$AUTH")
  [ "$DEL" = "200" ] && info "已回收临时设备 $d" || info "临时设备 $d 未回收（HTTP $DEL），可忽略"
done
DELP=$(http_code -X DELETE "$BASE/points/$PID" -H "$AUTH")
[ "$DELP" = "200" ] && info "已回收临时测点" || info "临时测点未回收（HTTP $DELP），可忽略"

summary
