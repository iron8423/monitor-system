#!/bin/bash
# 验收 04：告警闭环 —— 超限 -> 告警 -> 处置 -> 解除，以及防刷屏。
#   覆盖验收脚本第 3 条（超限自动生成警情、持续超限不刷屏）与第 4 条（处置留痕）。
#
#   为什么用「本次新建的临时测点」而不是种子点：种子规则 repeat_suppress_seconds=300，
#   同点同规则在 5 分钟内只要产生过警情（**含已解除的**）就不再触发（AlarmEngine#suppressed）。
#   沿用固定测点会导致 5 分钟内重跑必然「无警情」，那不是代码错而是防刷屏真的生效了。
#   新建点每轮都是干净的，断言因此可精确到条数。
#
#   验收第 3 条后半句「等级升高能升级」在 ⑨ 单独验证：同一测点同一测项未解除的警情只保留一条，
#   值继续恶化命中更高等级规则时**就地升级**（等级抬高 + 时间线追加 escalate），不另开平行警情。
#   该语义于 2026-09-10 与用户定案（升级只改等级、不改所属规则，恢复仍按最初触发那条规则判定）。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

TOKEN=$(login)
AUTH="Authorization: Bearer $TOKEN"

NP="P-ALM-$RUN_ID"
PID=$(curl -s -X POST "$BASE/points" -H "$AUTH" -H "$JSON" \
      -d "{\"objectId\":1,\"code\":\"$NP\",\"name\":\"告警验收临时测点\",\"type\":\"POINT_DEFORMATION\",\"enabled\":true}" \
      | data_of "['id']")
section "⓪ 临时测点 $NP -> pointId=$PID（同时验证验收第 7 条：新建即可用）"

section "① 上报 defo_mm=4.2 -> 命中种子规则 gte +3.0，应触发"
R=$(ingest_id "alm-$RUN_ID-1" "$NP" "2026-08-27T12:00:00+08:00" '"defo_mm":4.2')
check "上报 accepted" "1" "$(printf '%s' "$R" | data_of "['accepted']")"

ALARMS=$(curl -s "$BASE/alarms?pointId=$PID&status=PENDING" -H "$AUTH")
check "该点未解除警情数" "1" "$(printf '%s' "$ALARMS" | data_of "['total']")"
AID=$(printf '%s' "$ALARMS" | python3 -c "
import sys,json
print(max(r['id'] for r in json.load(sys.stdin)['data']['records']))")
check "警情级别沿用规则 level" "warning" "$(printf '%s' "$ALARMS" | python3 -c "
import sys,json;print([r for r in json.load(sys.stdin)['data']['records'] if r['id']==$AID][0]['level'])")"

section "② 警情详情：触发快照 + 时间线首条为系统触发"
DET=$(curl -s "$BASE/alarms/$AID" -H "$AUTH")
check "状态" "PENDING" "$(printf '%s' "$DET" | data_of "['status']")"
check "快照含触发值" "4.2" "$(printf '%s' "$DET" | data_of "['snapshot']['defo_mm']")"
check "时间线首条动作" "trigger" "$(printf '%s' "$DET" | python3 -c "
import sys,json;print(json.load(sys.stdin)['data']['timeline'][0]['action'])")"
check "时间线首条操作者" "system" "$(printf '%s' "$DET" | python3 -c "
import sys,json;print(json.load(sys.stdin)['data']['timeline'][0]['operator'])")"

section "③ 处置链 confirm -> dispatch -> research -> resolve（验收第 4 条留痕）"
# 动作->状态映射由 A 于 2026-09-10 定案（契约只列枚举、未写映射，见 AlarmConstants）
#
# 请求体里的 "operator":"验收员" 是**诱饵**：署名必须取登录身份，不能由调用方自报
# （否则任何人都能以别人的名义处置并留痕）。处置人一律从 token 解析，见 AlarmService#act。
# 这里刻意继续发送它，把「请求体被忽略」变成一条回归断言——真被采信时下面两条会红。
for pair in "confirm:CONFIRMED" "dispatch:PROCESSING" "research:OBSERVING" "resolve:RESOLVED"; do
  act="${pair%%:*}"; want="${pair##*:}"
  got=$(curl -s -X POST "$BASE/alarms/$AID/actions" -H "$AUTH" -H "$JSON" \
        -d "{\"action\":\"$act\",\"comment\":\"$act by acceptance\",\"operator\":\"验收员\"}" \
        | data_of "['status']")
  check "$act -> $want" "$want" "$got"
done
TL=$(curl -s "$BASE/alarms/$AID" -H "$AUTH" | python3 -c "
import sys,json;print(len(json.load(sys.stdin)['data']['timeline']))")
check "时间线留痕 5 条（触发 + 4 次处置）" "5" "$TL"
OPERATORS=$(curl -s "$BASE/alarms/$AID" -H "$AUTH" | python3 -c "
import sys,json;print('|'.join(r['operator'] for r in json.load(sys.stdin)['data']['timeline'][1:]))")
check "处置人取登录身份（请求体里的 operator 被忽略）" "$ADMIN_USER|$ADMIN_USER|$ADMIN_USER|$ADMIN_USER" "$OPERATORS"
check "处置人不得出现请求体里的冒名值" "0" "$(printf '%s' "$OPERATORS" | grep -c '验收员')"

section "④ 终态后再处置 / 非法动作 应被拒"
check "已解除后再 confirm -> 400" "400" "$(http_code -X POST "$BASE/alarms/$AID/actions" -H "$AUTH" -H "$JSON" -d '{"action":"confirm"}')"
check "非法动作 explode -> 400" "400" "$(http_code -X POST "$BASE/alarms/$AID/actions" -H "$AUTH" -H "$JSON" -d '{"action":"explode"}')"

section "⑤ 负向规则 lte -3.0：上报 defo_mm=-4.0 应触发"
ingest_id "alm-$RUN_ID-2" "$NP" "2026-08-27T12:05:00+08:00" '"defo_mm":-4.0' >/dev/null
check "该点未解除警情数" "1" "$(curl -s "$BASE/alarms?pointId=$PID&status=PENDING" -H "$AUTH" | data_of "['total']")"

section "⑥ 自动恢复：上报 defo_mm=0.5（>= 恢复值 -1.0）应自动解除"
ingest_id "alm-$RUN_ID-3" "$NP" "2026-08-27T12:10:00+08:00" '"defo_mm":0.5' >/dev/null
check "该点未解除警情归零" "0" "$(curl -s "$BASE/alarms?pointId=$PID&status=PENDING" -H "$AUTH" | data_of "['total']")"
# lastAction 是原始动作串（AlarmService#toVO 直接取 action_type），不是中文标签——
# 契约 §4 示例里的 "触发" 与代码不符，已在文档侧记录待改
check "解除动作记为 recover" "recover" "$(curl -s "$BASE/alarms?pointId=$PID" -H "$AUTH" | python3 -c "
import sys,json
rs=json.load(sys.stdin)['data']['records']
print([r for r in rs if r['status']=='RESOLVED'][0]['lastAction'])")"

section "⑦ 防刷屏：同点同规则抑制窗口内不重复触发（验收第 3 条后半）"
# 用 rate_mm_d 建规则：种子里没有覆盖该测项的规则，干扰为零
RID=$(curl -s -X POST "$BASE/alarm-rules" -H "$AUTH" -H "$JSON" -d "{
  \"name\":\"验收-防刷屏-$RUN_ID\",\"pointId\":$PID,\"metricCode\":\"rate_mm_d\",
  \"type\":\"THRESHOLD\",\"operator\":\"gte\",\"value\":0.5,\"recoveryValue\":0.4,
  \"level\":\"notice\",\"repeatSuppressSeconds\":300,\"enabled\":true}" | data_of "['id']")
info "自建规则 id=$RID（点 $NP / rate_mm_d / gte 0.5 / 抑制 300s）"
ingest_id "alm-$RUN_ID-4" "$NP" "2026-08-27T12:20:00+08:00" '"rate_mm_d":1.0' >/dev/null
check "首次超限产生警情" "1" "$(curl -s "$BASE/alarms?pointId=$PID&level=notice" -H "$AUTH" | data_of "['total']")"
NID=$(curl -s "$BASE/alarms?pointId=$PID&level=notice" -H "$AUTH" | python3 -c "
import sys,json;print(max(r['id'] for r in json.load(sys.stdin)['data']['records']))")
curl -s -o /dev/null -X POST "$BASE/alarms/$NID/actions" -H "$AUTH" -H "$JSON" -d '{"action":"resolve","comment":"腾出未解除位，单独验防刷屏"}'
ingest_id "alm-$RUN_ID-5" "$NP" "2026-08-27T12:21:00+08:00" '"rate_mm_d":1.0' >/dev/null
check "解除后窗口内再超限 -> 不新增（被抑制）" "1" \
  "$(curl -s "$BASE/alarms?pointId=$PID&level=notice" -H "$AUTH" | data_of "['total']")"

section "⑧ 规则 CRUD 与类型收敛"
check "规则列表含默认双向规则" "True" "$(curl -s "$BASE/alarm-rules" -H "$AUTH" | python3 -c "
import sys,json
rs=json.load(sys.stdin)['data']
print(any(r['operator']=='gte' and str(r['value']).startswith('3') for r in rs)
      and any(r['operator']=='lte' and str(r['value']).startswith('-3') for r in rs))")"
for t in RATE CHANGE; do
  check "建 $t 规则 -> 400（引擎不评估窗口类型，接口层须拦住）" "400" \
    "$(http_code -X POST "$BASE/alarm-rules" -H "$AUTH" -H "$JSON" \
       -d "{\"name\":\"试建-$t-$RUN_ID\",\"pointId\":$PID,\"metricCode\":\"defo_mm\",\"type\":\"$t\",\"operator\":\"gte\",\"value\":10.0,\"level\":\"alarm\",\"windowMinutes\":30}")"
done
NEWR=$(curl -s -X POST "$BASE/alarm-rules" -H "$AUTH" -H "$JSON" \
  -d "{\"name\":\"验收-THRESHOLD-$RUN_ID\",\"pointId\":$PID,\"metricCode\":\"rate_mm_d\",\"type\":\"THRESHOLD\",\"operator\":\"gte\",\"value\":5.0,\"level\":\"notice\",\"enabled\":true}")
check "建 THRESHOLD 规则业务码" "0" "$(printf '%s' "$NEWR" | code_of)"
check "回读 type" "THRESHOLD" "$(printf '%s' "$NEWR" | data_of "['type']")"
# 注意 NEWR 是**整个响应体**（上面两条断言要 `code_of` / `data_of` 读它），
# 末尾回收时要的是里面的 id，不能直接把 NEWR 当 id 用——当成 id 会拼出一个垃圾 URL，
# 删除静默失败，规则留在库里。这个坑是末尾那条「跑完不留自建规则」断言当场抓到的。
NEWR_ID=$(printf '%s' "$NEWR" | data_of "['id']")

section "⑨ 等级升级：同点同测项只保留一条未解除警情，命中更高等级规则时就地升级（验收第 3 条）"
# 换一个干净测点：本套件已在 $NP 上产生过 +3.0 规则的警情，同点同规则在抑制窗口内不会再触发，
# 沿用 $NP 的话「先开出 warning」这一步根本做不出来。
EP="P-ESC-$RUN_ID"
EPID=$(curl -s -X POST "$BASE/points" -H "$AUTH" -H "$JSON" \
       -d "{\"objectId\":1,\"code\":\"$EP\",\"name\":\"等级升级验收临时测点\",\"type\":\"POINT_DEFORMATION\",\"enabled\":true}" \
       | data_of "['id']")
# 4.2 越过 +3.0（warning）但不到 +5.0（alarm，V4 种子规则）
ingest_id "esc-$RUN_ID-1" "$EP" "2026-08-27T13:00:00+08:00" '"defo_mm":4.2' >/dev/null
check "先开出 warning 级警情" "1" "$(curl -s "$BASE/alarms?pointId=$EPID&status=PENDING" -H "$AUTH" | data_of "['total']")"
EID=$(curl -s "$BASE/alarms?pointId=$EPID&status=PENDING" -H "$AUTH" | python3 -c "
import sys,json;print(max(r['id'] for r in json.load(sys.stdin)['data']['records']))")
check "初始等级" "warning" "$(curl -s "$BASE/alarms/$EID" -H "$AUTH" | data_of "['level']")"
# 6.0 越过 +5.0 -> 就地升级，不得多出一条
ingest_id "esc-$RUN_ID-2" "$EP" "2026-08-27T13:05:00+08:00" '"defo_mm":6.0' >/dev/null
check "仍是同一条警情（没有多出平行警情）" "1" \
  "$(curl -s "$BASE/alarms?pointId=$EPID&status=PENDING" -H "$AUTH" | data_of "['total']")"
check "等级已升为 alarm" "alarm" "$(curl -s "$BASE/alarms/$EID" -H "$AUTH" | data_of "['level']")"
check "时间线记了 escalate" "True" "$(curl -s "$BASE/alarms/$EID" -H "$AUTH" | python3 -c "
import sys,json;print(any(t['action']=='escalate' for t in json.load(sys.stdin)['data']['timeline']))")"
# 恢复到 0.5：按**最初触发**那条规则（gte +3.0 / 恢复 +1.0）解除，而不是升级到的那条（恢复 +2.0）
ingest_id "esc-$RUN_ID-3" "$EP" "2026-08-27T13:10:00+08:00" '"defo_mm":0.5' >/dev/null
check "回落至最初规则恢复值内 -> 自动解除" "0" \
  "$(curl -s "$BASE/alarms?pointId=$EPID&status=PENDING" -H "$AUTH" | data_of "['total']")"
recycle_point "$EPID" "临时测点 $EP"

section "⑩ 鉴权"
check "警情列表无 JWT -> 401" "401" "$(http_code "$BASE/alarms")"
check "规则列表无 JWT -> 401" "401" "$(http_code "$BASE/alarm-rules")"

section "⑪ 角色 → 处置动作：后端强制（越权 403），权威表在 AlarmConstants.ROLE_ACTIONS"
# 这条曾经只活在前端：四个角色的动作差异写死在 utils/labels.js，后端 act() 不看角色，
# 于是「隐藏按钮」成了唯一屏障——改一行 localStorage 或直接发请求就能越权。
# 现在后端按角色强制放行，前端那张表只决定按钮显不显。
#
# 点号上限 64 字符（V5 起两处对齐；此前 measurement.point_code 只有 32，
# 33–64 的点号建得出来但上报 500，见 B-13 / 02 套件 ⑫）。这里的点号远在限内。
RP="P-ROLE-$RUN_ID"
RPID=$(curl -s -X POST "$BASE/points" -H "$AUTH" -H "$JSON" \
       -d "{\"objectId\":1,\"code\":\"$RP\",\"name\":\"角色验收临时测点\",\"type\":\"POINT_DEFORMATION\",\"enabled\":true}" \
       | data_of "['id']")
ingest_id "role-$RUN_ID" "$RP" "2026-08-29T09:00:00+08:00" '"defo_mm":9.0' >/dev/null
RAID=$(curl -s "$BASE/alarms?pointId=$RPID&status=PENDING" -H "$AUTH" | python3 -c "
import sys,json;print(max(r['id'] for r in json.load(sys.stdin)['data']['records']))")

# 越权断言放在放行断言**之前**：403 不改状态，同一条警情可以复用于全部越权探测；
# 若顺序反了，前面万一有人被放行成功，警情提前进终态会把后面全带成 400，报错就跑偏了。
for pair in "operator:research" "analyst:confirm" "analyst:dispatch" "analyst:handle" \
            "maintainer:confirm" "maintainer:research" "maintainer:dispatch"; do
  u="${pair%%:*}"; a="${pair##*:}"
  check "$u 越权 $a -> 403" "403" \
    "$(http_code -X POST "$BASE/alarms/$RAID/actions" -H "Authorization: Bearer $(login_as "$u")" -H "$JSON" -d "{\"action\":\"$a\"}")"
done
check "越权响应带可读文案（说的是角色无权，不是别的错）" "True" \
  "$(curl -s -X POST "$BASE/alarms/$RAID/actions" -H "Authorization: Bearer $(login_as maintainer)" -H "$JSON" \
     -d '{"action":"confirm"}' | python3 -c "
import sys,json
print('无权执行' in json.load(sys.stdin)['message'])")"
# 必须在下面「放行」循环**之前**查：admin:confirm 本身就会写一条 confirm，放后面必然自相矛盾
check "越权被拒后没有留痕（时间线里没有 confirm）" "False" \
  "$(curl -s "$BASE/alarms/$RAID" -H "$AUTH" | python3 -c "
import sys,json
print(any(t['action']=='confirm' for t in json.load(sys.stdin)['data']['timeline']))")"

# 放行：四个角色各做一个**非终态**动作，这样同一条警情够用
for pair in "admin:confirm" "operator:confirm" "analyst:research" "maintainer:handle"; do
  u="${pair%%:*}"; a="${pair##*:}"
  check "$u 合法 $a -> 200" "200" \
    "$(http_code -X POST "$BASE/alarms/$RAID/actions" -H "Authorization: Bearer $(login_as "$u")" -H "$JSON" -d "{\"action\":\"$a\"}")"
done
# 回收本套件自建的规则。
#
# 这里的点可以软删（`deleted` 列一置，读端点全看不见），**规则不行**：
# `alarm_rule` 表没有 `deleted` 列，DELETE 就是物理删除。此前⑦⑧各建一条、都不回收，
# 于是每跑一轮演示库就永久多留两条——跑到第 6 轮时管理端「告警规则」页签里
# 已经排着 12 条 `验收-*` 垃圾规则。它们指向的是已软删的测点，**不会再触发**
# （没有测点上报就没人拿它们去比），所以是演示污染而非正确性问题，但一样要清。
for rid in "$RID" "$NEWR_ID"; do
  [ -n "$rid" ] || continue
  DRC=$(http_code -X DELETE "$BASE/alarm-rules/$rid" -H "$AUTH")
  [ "$DRC" = "200" ] && info "已回收临时规则 id=$rid" || info "临时规则 id=$rid 未回收（HTTP $DRC），可忽略"
done

recycle_point "$RPID" "临时测点 $RP"
recycle_point "$PID"

# 套件自身卫生：跑完不该留下任何 `验收-` 前缀的规则。
# 这条**不是**在测产品，是在测本脚本自己有没有漏回收——加它是因为真的漏了很多轮。
check "跑完不留自建规则（套件自身卫生）" "0" \
  "$(curl -s "$BASE/alarm-rules" -H "$AUTH" | python3 -c "
import sys,json
rs=json.load(sys.stdin)['data']
print(sum(1 for r in rs if r['name'].startswith('验收-')))")"

summary
