#!/bin/bash
# 验收 08：模拟器链路 —— 阶段 1 验收链的**第一环**。
#
#   阶段 1 的判据是「模拟器 -> ingest(校验/去重) -> 落库 -> 规则触发 -> 警情生成 -> 处置留痕」。
#   01~07 套件全部用 curl 直接合成报文，等于**绕过了链子的起点**：链路的后半段验得很扎实，
#   但「模拟器」这一环从没被跑过（tools/radar_csv_replay 是回放器不是生成器，要真雷达 CSV）。
#   本套件用 tools/radar_simulator/radar_simulator.py 把起点补上，断言它造出来的数据
#   确实能走完整条链。
#
#   覆盖验收脚本第 1 条（上报即可查询）、第 2 条（幂等）、第 3 条（超限告警/升级/恢复），
#   外加质量闸门（SUSPECT 不参与告警判定）与模拟器自身的参数校验。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

SIM="$HERE/../radar_simulator/radar_simulator.py"
URL="$BASE/ingest/measurements"
[ -f "$SIM" ] || { printf '找不到模拟器 %s\n' "$SIM" >&2; exit 2; }

TOKEN=$(login)
AUTH="Authorization: Bearer $TOKEN"

# 模拟器是「连续造数」，所以每轮都新建临时测点，断言才能精确到条数——
# 沿用固定点会让上一轮的数据混进来，且可能撞上 300s 防刷屏窗口。
NP="P-SIM-$RUN_ID"
PID=$(curl -s -X POST "$BASE/points" -H "$AUTH" -H "$JSON" \
      -d "{\"objectId\":1,\"code\":\"$NP\",\"name\":\"模拟器验收临时测点\",\"type\":\"POINT_DEFORMATION\",\"enabled\":true}" \
      | data_of "['id']")
section "⓪ 临时测点 $NP -> pointId=$PID，设备 radar-001（种子）"

series_n() { curl -s "$BASE/points/$1/series?metricCode=defo_mm" -H "$AUTH" \
             | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']['points']))"; }
alarms_n() { curl -s "$BASE/alarms?pointId=$1" -H "$AUTH" | data_of "['total']"; }
sim_at() { python3 "$SIM" --url "$URL" --key "$INGEST_KEY" --points "$1" "${@:2}"; }
sim() { sim_at "$NP" "$@"; }

section "① 模拟器造数 -> 落库 -> 立即可查（验收第 1 条）"
OUT=$(sim --once --interval 0 --seed 11 2>&1)
info "$(printf '%s' "$OUT" | grep '第 1 轮' | sed 's/^ *//')"
# 一条消息含 2 个测项 -> 拆 2 行落库（message-contract §5 D2）
check "1 条消息拆 2 行落库" "2" "$(printf '%s' "$OUT" | grep -o 'accepted=[0-9]*' | head -1 | cut -d= -f2)"
check "点号被接受（rejected=0）" "0" "$(printf '%s' "$OUT" | grep -o 'rejected=[0-9]*' | head -1 | cut -d= -f2)"
check "latest 取到模拟器造的值" "True" "$(curl -s "$BASE/points/$PID/latest" -H "$AUTH" | python3 -c "
import sys,json;print(json.load(sys.stdin)['data']['latest']['defo_mm'] is not None)")"
check "latest 含同刻兄弟测项 rate_mm_d" "True" "$(curl -s "$BASE/points/$PID/latest" -H "$AUTH" | python3 -c "
import sys,json;print(json.load(sys.stdin)['data']['latest'].get('rate_mm_d') is not None)")"
check "曲线可取到 1 点" "1" "$(series_n "$PID")"

section "② 连续造数：轮数 = 曲线点数，时间刻度按 --step-minutes 走"
OUT=$(sim --count 3 --interval 0 --seed 12 --step-minutes 60 2>&1)
info "$(printf '%s' "$OUT" | grep '模拟时钟' | sed 's/^ *//')"
check "3 轮后曲线共 4 点（1 + 3）" "4" "$(series_n "$PID")"
# collectTime 用模拟时钟：每轮 +60 分钟，最后一跳应正好 3600 秒
check "相邻采集间隔 = 3600s（模拟时钟生效）" "3600" "$(curl -s "$BASE/points/$PID/series?metricCode=defo_mm" -H "$AUTH" | python3 -c "
import sys,json
from datetime import datetime
pts=json.load(sys.stdin)['data']['points']
a=datetime.fromisoformat(pts[-2]['t']); b=datetime.fromisoformat(pts[-1]['t'])
print(int((b-a).total_seconds()))")"

section "③ 幂等：同 messageId 原样重发不重复写（验收第 2 条）"
BEFORE=$(series_n "$PID")
OUT=$(sim --once --interval 0 --seed 13 --inject-duplicate 2>&1)
info "$(printf '%s' "$OUT" | grep '第 1 轮' | sed 's/^ *//')"
check "重发被计为 duplicates" "1" "$(printf '%s' "$OUT" | grep -o 'duplicates=[0-9]*' | head -1 | cut -d= -f2)"
check "只多了 1 点（重复那条没落库）" "$((BEFORE + 1))" "$(series_n "$PID")"

section "④ 质量闸门：SUSPECT 的超限值不参与告警判定（message-contract §3）"
# SUSPECT 与对照组必须落在**不同测点**：对照组会真的产生警情，
# 而同一 (测点,规则) 在 300s 内产生过警情就不再触发（防刷屏），会把后一段断言带偏。
QP="P-SIMQ-$RUN_ID"
QPID=$(curl -s -X POST "$BASE/points" -H "$AUTH" -H "$JSON" \
       -d "{\"objectId\":1,\"code\":\"$QP\",\"name\":\"模拟器质量闸门临时测点\",\"type\":\"POINT_DEFORMATION\",\"enabled\":true}" \
       | data_of "['id']")
sim_at "$QP" --once --interval 0 --seed 14 --inject-overlimit --overlimit-point "$QP" \
    --overlimit-steps "4.2" --overlimit-hold 99 --inject-suspect >/dev/null 2>&1
check "SUSPECT + defo=4.2 -> 不产生警情" "0" "$(alarms_n "$QPID")"
sim_at "$QP" --once --interval 0 --seed 15 --inject-overlimit --overlimit-point "$QP" \
    --overlimit-steps "4.2" --overlimit-hold 99 >/dev/null 2>&1
check "对照组：同值但质量正常 -> 产生 1 条警情" "1" "$(alarms_n "$QPID")"
recycle_point "$QPID" "临时测点 $QP"

section "⑤ 超限 -> 升级 -> 恢复 全链（验收第 3 条）"
# 阶梯 3.2 -> 4.2 -> 5.6：第一个跨过种子规则 gte +3.0（warning），最后一个跨过 V4 的 gte +5.0（alarm）
OUT=$(sim --count 4 --interval 0 --seed 16 --inject-overlimit --overlimit-point "$NP" \
      --overlimit-steps "3.2,4.2,5.6" --overlimit-hold 1 --recover-after 3 2>&1)
printf '%s\n' "$OUT" | grep '第 [0-9] 轮' | sed 's/^ */  /'
check "全程只产生 1 条警情（不多开平行警情）" "1" "$(alarms_n "$PID")"
EID=$(curl -s "$BASE/alarms?pointId=$PID" -H "$AUTH" | python3 -c "
import sys,json;print(json.load(sys.stdin)['data']['records'][0]['id'])")
check "等级升到 alarm（跨过 +5.0 那条规则）" "alarm" "$(curl -s "$BASE/alarms/$EID" -H "$AUTH" | data_of "['level']")"
check "回落至 +0.5 -> 自动解除" "RESOLVED" "$(curl -s "$BASE/alarms/$EID" -H "$AUTH" | data_of "['status']")"
check "时间线含 trigger/escalate/recover" "['trigger', 'escalate', 'recover']" \
  "$(curl -s "$BASE/alarms/$EID" -H "$AUTH" | python3 -c "
import sys,json;print([t['action'] for t in json.load(sys.stdin)['data']['timeline']])")"

section "⑥ 模拟器自身的参数校验（防「静默没注入」）"
# 注入点不在点集里 = 注入完全不生效，但脚本会照常跑完、输出一片正常——必须当场报错
python3 "$SIM" --dry-run --points P-X --inject-overlimit --overlimit-point P-Y >/dev/null 2>&1
check "注入点不在 --points 内 -> 退出码 2" "2" "$?"
python3 "$SIM" --dry-run --points "" >/dev/null 2>&1
check "测点集为空 -> 退出码 2" "2" "$?"

recycle_point "$PID" "临时测点 $NP"

summary
