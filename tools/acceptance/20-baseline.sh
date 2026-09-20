#!/bin/bash
# 验收 20：测量基准版本（复查清单 P1-4）。
#
#   要验的是「换过基准」这件事**留得下痕迹、且能被曲线看见**——雷达上报的 defo_mm 是
#   相对某次基准的累计形变，换反射器/重装设备之后它从 0 重来，而平台看到的只是一条
#   继续延伸的曲线。分不出"稳定了"和"换了基准"，正是这一条要防的错误结论。
#
#   本套件跑在共享的 BASE 实例上（不像 16/19 需要自带后端）：它不改任何配置。
#   临时测点在末尾回收。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

TOKEN=$(login)
AUTH="Authorization: Bearer $TOKEN"
MTOKEN=$(login_as maintainer)
MAUTH="Authorization: Bearer $MTOKEN"
OP_TOKEN=$(login_as operator)
OP_AUTH="Authorization: Bearer $OP_TOKEN"
OUT_TOKEN=$(login_as outsider)
OUT_AUTH="Authorization: Bearer $OUT_TOKEN"
# 角色令牌拿不到就当场退出（退出码 2 = 环境问题）：空令牌会让下面的 403 断言
# 因为"根本没登录"而变绿，那是完全错误的原因
for t in "$MTOKEN" "$OP_TOKEN" "$OUT_TOKEN"; do
  [ -n "$t" ] || { printf '%s某个演示账号登录失败，无法验证角色边界%s\n' "$C_RED" "$C_OFF" >&2; exit 2; }
done

# 相对现在的 ISO8601（带 +08:00），复用 19 号套件的辅助脚本
TS() { python3 "$HERE/support/ts_offset.py" "$@"; }

section "⓪ 夹具：项目 1 下的临时测点 + 一条当前测值"
NP="P-BASE-$RUN_ID"
PID=$(curl -s -X POST "$BASE/points" -H "$AUTH" -H "$JSON" \
      -d "{\"objectId\":1,\"code\":\"$NP\",\"name\":\"基准验收临时测点\",\"type\":\"POINT_DEFORMATION\",\"enabled\":true}" \
      | data_of "['id']")
check "临时测点已建" "true" "$([ -n "$PID" ] && [ "$PID" != "None" ] && echo true || echo false)"
# 读数要灌在"基准生效之前"：换基准那一刻的读数取自 effectiveFrom 及之前的最新一行。
# 原来灌在 now、基准取 now-1h，于是回填不到（实得 null）——这不是代码错，是夹具的时间关系错。
ingest2 "base-$RUN_ID" "$NP" "$(TS --minutes -10)" 2.5 0.1 >/dev/null
info "pointId=$PID；已在 -10 分钟处灌一条 defo_mm=2.5"

section "① 从未登记过：data 为 null（不是 404——「没有基准」是正常状态）"
check "当前基准为空（业务码 0 且 data 为 null，不是 404）" "True" \
  "$(curl -s "$BASE/points/$PID/baseline" -H "$AUTH" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('code')==0 and d.get('data') is None)")"
check "原因白名单可读（给前端做下拉）" "true" \
  "$(curl -s "$BASE/points/$PID/baseline/reasons" -H "$AUTH" | python3 -c "import sys,json;d=json.load(sys.stdin)['data'];print(str('REFLECTOR_REPLACED' in d and 'DATA_RESET' in d).lower())")"

section "② 登记：MAINTAINER 可以，且操作人取登录身份"
EF=$(TS --minutes -5)
CREATED=$(curl -s -X POST "$BASE/points/$PID/baseline" -H "$MAUTH" -H "$JSON" \
  -d "{\"effectiveFrom\":\"$EF\",\"reason\":\"REFLECTOR_REPLACED\",\"note\":\"验收：更换反射器\",\"operator\":\"冒名顶替\"}")
check "登记成功" "0" "$(printf '%s' "$CREATED" | code_of)"
check "原因带回中文标签" "更换反射器" "$(printf '%s' "$CREATED" | data_of "['reasonLabel']")"
# 请求体里塞了 operator=冒名顶替，必须以登录身份为准（与处置留痕同一条规矩）
check "操作人取登录身份（忽略请求体）" "maintainer" "$(printf '%s' "$CREATED" | data_of "['operator']")"
check "回填了换基准时的读数（上一条 defo_mm=2.5）" "2.5" "$(printf '%s' "$CREATED" | data_of "['baselineValueMm']")"
check "当前基准就是它" "$EF" "$(curl -s "$BASE/points/$PID/baseline" -H "$AUTH" | data_of "['effectiveFrom']")"

section "③ 原因白名单：自由文本被拒"
check "中文自由文本 -> 400" "400" \
  "$(http_code -X POST "$BASE/points/$PID/baseline" -H "$AUTH" -H "$JSON" \
     -d "{\"reason\":\"换了个东西\"}")"
check "短码大小写不敏感（data_reset 可用）" "0" \
  "$(curl -s -X POST "$BASE/points/$PID/baseline" -H "$AUTH" -H "$JSON" \
     -d "{\"effectiveFrom\":\"$(TS --hours -2)\",\"reason\":\"data_reset\"}" | code_of)"

section "④ 生效时间不能落在未来"
check "比现在晚 2 小时 -> 400" "400" \
  "$(http_code -X POST "$BASE/points/$PID/baseline" -H "$AUTH" -H "$JSON" \
     -d "{\"effectiveFrom\":\"$(TS --hours 2)\",\"reason\":\"MANUAL\"}")"

section "⑤ 同一时刻重复登记 -> 400（V24 唯一约束）"
check "同一 effectiveFrom 再来一次 -> 400" "400" \
  "$(http_code -X POST "$BASE/points/$PID/baseline" -H "$AUTH" -H "$JSON" \
     -d "{\"effectiveFrom\":\"$EF\",\"reason\":\"MANUAL\"}")"

section "⑥ 历史：新的在前"
check "历史条数" "2" \
  "$(curl -s "$BASE/points/$PID/baseline/history" -H "$AUTH" | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']))")"
check "第一条是最新的那条（-5 分钟的反射器更换）" "$EF" \
  "$(curl -s "$BASE/points/$PID/baseline/history" -H "$AUTH" | python3 -c "import sys,json;print(json.load(sys.stdin)['data'][0]['effectiveFrom'])")"

section "⑦ 曲线要能看见基准（默认 24h 窗口内，两条都落在里面）"
SER=$(curl -s "$BASE/points/$PID/series?metricCode=defo_mm" -H "$AUTH")
check "series 带出窗口内的基准条数" "2" \
  "$(printf '%s' "$SER" | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data'].get('baselines') or []))")"
check "带出原因标签（前端直接显示）" "true" \
  "$(printf '%s' "$SER" | python3 -c "import sys,json;bs=json.load(sys.stdin)['data']['baselines'];print(str(any(b['reasonLabel']=='更换反射器' for b in bs)).lower())")"
# 窗口边界的两侧都要钉：-5 分钟那条在 [-30min, now] 里、但不在 [-3min, now] 里。
# 只测一侧的话，"把窗口内所有基准都返回"（不看时间）也会绿。
check "窗口 [-30min, now] 内有 1 条（-5 分钟那条）" "1" \
  "$(curl -s "$BASE/points/$PID/series?metricCode=defo_mm&from=$(TS --minutes -30 --urlencode)" -H "$AUTH" | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data'].get('baselines') or []))")"
check "窗口 [-3min, now] 内 0 条（-5 分钟那条已在窗口外）" "0" \
  "$(curl -s "$BASE/points/$PID/series?metricCode=defo_mm&from=$(TS --minutes -3 --urlencode)" -H "$AUTH" | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data'].get('baselines') or []))")"

section "⑧ 角色边界：能登记的是 ADMIN / MAINTAINER，读开放"
check "值班员读当前基准 -> 200" "200" "$(http_code "$BASE/points/$PID/baseline" -H "$OP_AUTH")"
check "值班员登记 -> 403" "403" \
  "$(http_code -X POST "$BASE/points/$PID/baseline" -H "$OP_AUTH" -H "$JSON" -d "{\"reason\":\"MANUAL\"}")"
check "outsider 读基准 -> 403（数据范围）" "403" "$(http_code "$BASE/points/$PID/baseline" -H "$OUT_AUTH")"
check "无 JWT -> 401" "401" "$(http_code "$BASE/points/$PID/baseline")"
check "不存在的测点 -> 404" "404" "$(http_code "$BASE/points/99999/baseline" -H "$AUTH")"

section "⑨ 登记写审计（谁在什么时候登记了基准）"
check "审计里能找到「登记测量基准」" "true" \
  "$(curl -s "$BASE/audit-logs?pageNum=1&pageSize=50&targetType=MeasurementBaseline" -H "$AUTH" | python3 -c "import sys,json;rs=json.load(sys.stdin)['data']['records'];print(str(any(r.get('action')=='登记测量基准' for r in rs)).lower())")"

section "⑩ 回收临时测点"
curl -s -o /dev/null -X DELETE "$BASE/points/$PID" -H "$AUTH"
info "已回收临时测点 $NP"

summary
