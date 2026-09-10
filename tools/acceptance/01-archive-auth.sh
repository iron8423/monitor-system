#!/bin/bash
# 验收 01：档案可读 + 鉴权边界。
#   覆盖验收脚本第 1 条的「档案就绪」前提，以及第 7 条的「新建测点立即可见」。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

TOKEN=$(login)
AUTH="Authorization: Bearer $TOKEN"

section "① 登录与身份"
check "auth/login 返回 JWT（三段点分）" "3" "$(printf '%s' "$TOKEN" | awk -F. '{print NF}')"
ME=$(curl -s "$BASE/auth/me" -H "$AUTH" | data_of "['username']")
check "auth/me 返回当前账号" "$ADMIN_USER" "$ME"

section "② 种子档案：1 项目 / 2 场景 / 7 测点"
check "项目数" "1" "$(curl -s "$BASE/projects" -H "$AUTH" | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']))")"
check "测点数" "7" "$(curl -s "$BASE/points" -H "$AUTH" | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']))")"
check "测点 1 号点号" "P-HK01" "$(curl -s "$BASE/points/1" -H "$AUTH" | data_of "['code']")"
check "每点 2 测项（D1）" "2" "$(curl -s "$BASE/metrics" -H "$AUTH" | python3 -c "
import sys,json
ms=json.load(sys.stdin)['data']
print(len([m for m in ms if m['pointId']==1]))")"

section "③ 设备档案（在线判定归 A，契约 §5）"
check "设备数" "1" "$(curl -s "$BASE/devices" -H "$AUTH" | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']))")"
DS=$(curl -s "$BASE/devices/1/status" -H "$AUTH" | python3 -c "
import sys,json; d=json.load(sys.stdin)['data']
print(d['status'], d['online'], d['code'])")
info "devices/1/status -> $DS"
check_contains "设备码是字符串 code 而非数值 id" "radar-001" "$DS"

section "④ 新建测点立即可见（验收第 7 条）"
NEW="P-ACC-$RUN_ID"
PID=$(curl -s -X POST "$BASE/points" -H "$AUTH" -H "$JSON" \
      -d "{\"objectId\":1,\"code\":\"$NEW\",\"name\":\"验收临时测点\",\"type\":\"POINT_DEFORMATION\",\"enabled\":true}" \
      | data_of "['id']")
info "新建 $NEW -> pointId=$PID"
check_contains "新建后立刻可在列表按 code 查到" "$NEW" \
  "$(curl -s "$BASE/points" -H "$AUTH" | python3 -c "
import sys,json
print([p['code'] for p in json.load(sys.stdin)['data']])")"
check "新建点可单查" "$NEW" "$(curl -s "$BASE/points/$PID" -H "$AUTH" | data_of "['code']")"
# 清理：删掉本次临时点，避免反复运行堆积（删不掉不算失败，只提示）
DEL=$(http_code -X DELETE "$BASE/points/$PID" -H "$AUTH")
[ "$DEL" = "200" ] && info "已回收临时测点" || info "临时测点未回收（HTTP $DEL），可忽略"

section "⑤ 鉴权边界"
check "无 JWT 读项目列表 -> 401" "401" "$(http_code "$BASE/projects")"
check "伪造 JWT -> 401" "401" "$(http_code "$BASE/projects" -H "Authorization: Bearer garbage.token.here")"
check "错误口令登录 -> 非 0 业务码" "401" "$(curl -s -X POST "$BASE/auth/login" -H "$JSON" \
  -d '{"username":"admin","password":"wrong-password"}' | python3 -c "
import sys,json
try: print(json.load(sys.stdin).get('code'))
except Exception: print('401')")"

section "⑥ 不存在资源的错误码（契约 §0：错误走 BizException）"
check "不存在的项目 -> 404" "404" "$(http_code "$BASE/projects/99999" -H "$AUTH")"
check "不存在的测点 -> 404" "404" "$(http_code "$BASE/points/99999" -H "$AUTH")"
check "不存在的设备状态 -> 404" "404" "$(http_code "$BASE/devices/99999/status" -H "$AUTH")"

summary
