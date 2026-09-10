#!/bin/bash
# 验收 02：接入落库与幂等。
#   覆盖验收脚本第 1 条（上报 → 数据可查）与第 2 条（重复消息不产生重复测量值/告警）。
#   契约依据：《B侧接口契约_M0》§2（拆行、幂等 device_id+message_id、X-Ingest-Key）。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

TOKEN=$(login)
AUTH="Authorization: Bearer $TOKEN"

# 造数落在本次新建的临时测点上。固定种子点 + 固定 collectTime 时，messageId 唯一只能保证
# 「同一条消息不重复写」，数据仍会跨轮堆积在同一时刻，于是 latest 取到哪一行、series 数几条
# 都随轮次变化（双跑实测暴露过：第 2 轮 02 挂了 3 条）。每轮新点则断言可精确到条数。
POINT="P-ING-$RUN_ID"
PID=$(curl -s -X POST "$BASE/points" -H "$AUTH" -H "$JSON" \
      -d "{\"objectId\":1,\"code\":\"$POINT\",\"name\":\"接入验收临时测点\",\"type\":\"POINT_DEFORMATION\",\"enabled\":true}" \
      | data_of "['id']")
section "⓪ 临时测点 $POINT -> pointId=$PID"

section "① 一条含 2 测项的消息 -> 落库拆 2 行（契约 §2 D2/D3）"
MID="ing2-$RUN_ID-a"
R=$(ingest2 "$MID" "$POINT" "2026-08-27T14:00:00+08:00" 0.19 0.05)
check "首次上报 accepted" "2" "$(printf '%s' "$R" | data_of "['accepted']")"
check "首次上报 duplicates" "0" "$(printf '%s' "$R" | data_of "['duplicates']")"
check "结果条目状态 OK" "OK" "$(printf '%s' "$R" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['results'][0]['status'])")"

section "② 同 messageId 重发 -> 整条去重，不重复写（验收第 2 条）"
R2=$(ingest2 "$MID" "$POINT" "2026-08-27T14:00:00+08:00" 0.19 0.05)
check "重发 accepted" "0" "$(printf '%s' "$R2" | data_of "['accepted']")"
# duplicates 按【消息】计（+1/条），而 accepted 按【落库行数】计（+2/条）——
# 同一条 2 测项消息被去重时是 duplicates=1 而不是 2。量纲不同，契约 §2 未写明，
# 已记入工作日志待补文档（不影响幂等本身：数据确实没重复写）
check "重发 duplicates（按消息计）" "1" "$(printf '%s' "$R2" | data_of "['duplicates']")"
check "重发仍逐条给结果" "DUPLICATE" "$(printf '%s' "$R2" | python3 -c "
import sys,json;print(json.load(sys.stdin)['data']['results'][0]['status'])")"
check "去重后没有多写一行（latest 仍是单份数据）" "1" "$(curl -s "$BASE/points/$PID/series?from=2026-08-27T13:59:00%2B08:00&to=2026-08-27T14:01:00%2B08:00&metricCode=defo_mm" -H "$AUTH" \
  | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']['points']))")"

section "③ 上报后立即可查（latest 重组出全部测项）"
L=$(curl -s "$BASE/points/$PID/latest" -H "$AUTH" | python3 -c "
import sys,json; d=json.load(sys.stdin)['data']['latest']
print(d.get('defo_mm'), d.get('rate_mm_d'), d.get('collectTime'))")
info "latest -> $L"
check_contains "latest 含报的 defo_mm 值" "0.19" "$L"
check_contains "latest 同刻含 rate_mm_d 兄弟行" "0.05" "$L"

section "④ 上报 → 时序曲线可取到（验收第 1 条）"
check_contains "series 含本次时点" "2026-08-27T14:00:00" \
  "$(curl -s "$BASE/points/$PID/series?from=2026-08-27T00:00:00%2B08:00&to=2026-08-28T00:00:00%2B08:00" -H "$AUTH" \
     | python3 -c "import sys,json;print([p['t'] for p in json.load(sys.stdin)['data']['points']])")"

section "⑤ 批量上报：正常与拒绝混装，逐条给结果"
MIDB="ing2-$RUN_ID-b"
RB=$(curl -s -X POST "$BASE/ingest/measurements" -H "$JSON" -H "$KEY" -d "{\"items\":[
  {\"messageId\":\"$MIDB-1\",\"deviceId\":\"radar-001\",\"pointCode\":\"$POINT\",\"collectTime\":\"2026-08-27T14:10:00+08:00\",\"metrics\":{\"defo_mm\":0.21},\"quality\":\"VALID\"},
  {\"messageId\":\"$MIDB-2\",\"deviceId\":\"radar-001\",\"pointCode\":\"NO-SUCH-POINT\",\"collectTime\":\"2026-08-27T14:10:00+08:00\",\"metrics\":{\"defo_mm\":0.21},\"quality\":\"VALID\"}]}")
check "批量 accepted" "1" "$(printf '%s' "$RB" | data_of "['accepted']")"
check "批量 rejected" "1" "$(printf '%s' "$RB" | data_of "['rejected']")"
check "未知点号 -> REJECTED" "REJECTED" "$(printf '%s' "$RB" | python3 -c "
import sys,json
print([r['status'] for r in json.load(sys.stdin)['data']['results'] if r['pointCode']=='NO-SUCH-POINT'][0])")"

section "⑥ 未知设备 -> REJECTED（契约 §2 边界）"
check "unknown deviceId" "REJECTED" \
  "$(ingest_id "ing2-$RUN_ID-c" "$POINT" "2026-08-27T14:20:00+08:00" '"defo_mm":0.5' "nope-999" \
     | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['results'][0]['status'])")"

section "⑦ 接入鉴权：X-Ingest-Key（契约 §2 / D7）"
BODY="{\"items\":[{\"messageId\":\"ing2-$RUN_ID-d\",\"deviceId\":\"radar-001\",\"pointCode\":\"$POINT\",\"collectTime\":\"2026-08-27T14:30:00+08:00\",\"metrics\":{\"defo_mm\":0.3}}]}"
check "不带 X-Ingest-Key -> 401" "401" \
  "$(http_code -X POST "$BASE/ingest/measurements" -H "$JSON" -d "$BODY")"
check "错误 X-Ingest-Key -> 401" "401" \
  "$(http_code -X POST "$BASE/ingest/measurements" -H "$JSON" -H 'X-Ingest-Key: wrong-key' -d "$BODY")"

section "⑧ 质量闸门：SUSPECT 不参与告警判定（message-contract §3）"
# 用一个必然超限的值但标 SUSPECT：不应产生警情
PS=$(curl -s "$BASE/alarms?pointId=$PID&status=PENDING" -H "$AUTH" | data_of "['total']")
curl -s -o /dev/null -X POST "$BASE/ingest/measurements" -H "$JSON" -H "$KEY" -d \
  "{\"items\":[{\"messageId\":\"ing2-$RUN_ID-s\",\"deviceId\":\"radar-001\",\"pointCode\":\"$POINT\",\"collectTime\":\"2026-08-27T14:40:00+08:00\",\"metrics\":{\"defo_mm\":9.9},\"quality\":\"SUSPECT\"}]}"
check "SUSPECT 超限值不产生警情" "$PS" \
  "$(curl -s "$BASE/alarms?pointId=$PID&status=PENDING" -H "$AUTH" | data_of "['total']")"

section "⑨ 带时区往返：非 +08 偏移必须换算，不能只留墙上时间"
# 契约 §0「时间 ISO8601 带时区」+ Times.ZONE=Asia/Shanghai：06:00Z 与 14:00+08:00 是同一时刻，
# 读出时必须还是 14:00+08:00。若只取 OffsetDateTime.toLocalDateTime()（丢偏移），
# 会存成 06:00 并被当 +08:00 渲染 —— 整错 8 小时；对设备在线判定更要命：
# last_report_time 落到 8 小时前，而 DeviceStatusPolicy 的在线窗口只有 5 分钟，
# 设备一直在报数却永远显示离线。
ingest_id "ing2-$RUN_ID-utc" "$POINT" "2026-08-27T06:00:00Z" '"defo_mm":0.4' >/dev/null
GOT=$(curl -s "$BASE/points/$PID/series?from=2026-08-27T00:00:00%2B08:00&to=2026-08-28T00:00:00%2B08:00" -H "$AUTH" \
      | python3 -c "
import sys,json
ts=[p['t'] for p in json.load(sys.stdin)['data']['points'] if p['v']==0.4]
print(ts[0] if ts else '<未找到>')")
check "06:00Z 应存为并回读为 14:00+08:00" "2026-08-27T14:00:00+08:00" "$GOT"

DEL=$(http_code -X DELETE "$BASE/points/$PID" -H "$AUTH")
[ "$DEL" = "200" ] && info "已回收临时测点" || info "临时测点未回收（HTTP $DEL），可忽略"

summary
