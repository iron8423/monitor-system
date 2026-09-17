#!/bin/bash
# 验收 15：用户管理（清单第 13 条的后半 —— 管理员看/改别人的账号）。
#
# 覆盖：接口只对 ADMIN 开、列表不外泄凭据、建号、改资料、停用**立刻**生效
# （旧令牌下一个请求就 401，不必等 24h 过期），以及三条自锁保护
# （不能停用自己 / 不能改自己的角色 / 不能删自己）。
#
# 为什么这些断言要进套件：这是全仓**唯一**一处「管理员能改别人账号」的入口——
# 改错了就是把同事关在门外、或者把自己关在门外；而它全是写操作，人工点一遍很难覆盖边界。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

TOKEN=$(login)
AUTH="Authorization: Bearer $TOKEN"

# 临时账号：名字带 RUN_ID，跑多少轮都不会撞车（撞了会红，说明上一轮没清干净）
TMP_USER="acc${RUN_ID//-/}"
TMP_PASS="Acc-${RUN_ID}-Pw1"     # ≥ 8 位
TMP_ID=""

cleanup_user() {
  [ -n "$TMP_ID" ] || return 0
  curl -s -o /dev/null -X DELETE "$BASE/users/$TMP_ID" -H "$AUTH" \
    && info "已删除临时账号 $TMP_USER（逻辑删除）"
}
trap cleanup_user EXIT

# 从 stdin 的信封里取 users 列表，做一段断言表达式（省得每条都写一个大 here-python）
users_query() {
  python3 -c "
import sys, json
rows = json.load(sys.stdin)['data']
print($1)"
}

section "① 接口只对管理员开、且不外泄凭据"

check "非管理员访问 /users -> 403" "403" \
  "$(http_code "$BASE/users" -H "Authorization: Bearer $(login_as operator)")"
check "未带令牌 -> 401" "401" "$(http_code "$BASE/users")"

USERS=$(curl -s "$BASE/users" -H "$AUTH")
check "列表含 5 个种子账号（4 演示 + outsider）" "5" \
  "$(printf '%s' "$USERS" | users_query "len([r for r in rows if r['username'] in {'admin','operator','analyst','maintainer','outsider'}])")"
# 口令与令牌版本绝不出现在读接口里——漏了就是凭据外泄，而界面上看不出任何异常
check "列表不含 password / tokenVersion 字段" "clean" \
  "$(printf '%s' "$USERS" | users_query "','.join(sorted({k for r in rows for k in r if k.lower() in ('password','tokenversion')})) or 'clean'")"
check "列表带出公司名（组织名，不是 id）" "清远电厂" \
  "$(printf '%s' "$USERS" | users_query "[r['organizationName'] for r in rows if r['username'] == 'admin'][0]")"

section "② 建号：重名 / 弱口令 / 非法角色都挡住"

check "重名 -> 400" "400" \
  "$(http_code -X POST "$BASE/users" -H "$AUTH" -H "$JSON" \
     -d "{\"username\":\"admin\",\"password\":\"$TMP_PASS\",\"displayName\":\"重名\",\"role\":\"OPERATOR\"}")"
check "初始口令不足 8 位 -> 400" "400" \
  "$(http_code -X POST "$BASE/users" -H "$AUTH" -H "$JSON" \
     -d "{\"username\":\"$TMP_USER\",\"password\":\"short7x\",\"displayName\":\"临时\",\"role\":\"OPERATOR\"}")"
check "角色非法 -> 400" "400" \
  "$(http_code -X POST "$BASE/users" -H "$AUTH" -H "$JSON" \
     -d "{\"username\":\"$TMP_USER\",\"password\":\"$TMP_PASS\",\"displayName\":\"临时\",\"role\":\"SUPERUSER\"}")"

CREATED=$(curl -s -X POST "$BASE/users" -H "$AUTH" -H "$JSON" \
  -d "{\"username\":\"$TMP_USER\",\"password\":\"$TMP_PASS\",\"displayName\":\"验收临时账号\",\"role\":\"OPERATOR\",\"organizationId\":1,\"jobTitle\":\"待定\",\"phone\":\"138-0000-9999\",\"email\":\"tmp@example.com\"}")
TMP_ID=$(printf '%s' "$CREATED" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['id'])" 2>/dev/null)
check "建号成功并返回 id" "yes" "$([ -n "$TMP_ID" ] && echo yes || echo no)"
check "初始资料已落库（岗位 + 电话）" "待定 / 138-0000-9999" \
  "$(curl -s "$BASE/users/$TMP_ID" -H "$AUTH" | python3 -c "
import sys, json
d = json.load(sys.stdin)['data']
print((d.get('jobTitle') or '—') + ' / ' + (d.get('phone') or '—'))")"
check "新账号能用初始口令登录（三段点分）" "3" \
  "$(printf '%s' "$(login_as "$TMP_USER" "$TMP_PASS")" | awk -F. '{print NF}')"
check "新账号是非管理员 -> 访问 /users 仍 403" "403" \
  "$(http_code "$BASE/users" -H "Authorization: Bearer $(login_as "$TMP_USER" "$TMP_PASS")")"

section "③ 改资料 / 停用：立刻生效"

TMP_BODY="{\"displayName\":\"验收临时账号\",\"role\":\"OPERATOR\",\"organizationId\":1,\"jobTitle\":\"值班员（改）\",\"phone\":\"138-0000-8888\",\"email\":\"tmp2@example.com\",\"enabled\":"
check "改资料 -> 200" "200" \
  "$(http_code -X PUT "$BASE/users/$TMP_ID" -H "$AUTH" -H "$JSON" -d "${TMP_BODY}true}")"
check "改动可在详情读回" "值班员（改） / 138-0000-8888" \
  "$(curl -s "$BASE/users/$TMP_ID" -H "$AUTH" | python3 -c "
import sys, json
d = json.load(sys.stdin)['data']
print((d.get('jobTitle') or '—') + ' / ' + (d.get('phone') or '—'))")"

TMP_TOKEN=$(login_as "$TMP_USER" "$TMP_PASS")
check "停用该账号 -> 200" "200" \
  "$(http_code -X PUT "$BASE/users/$TMP_ID" -H "$AUTH" -H "$JSON" -d "${TMP_BODY}false}")"
# 这两条是这个功能的要害：停用必须**立刻**生效，而不是等令牌寿命（24h）走完
check "停用后旧令牌随即 401" "401" \
  "$(http_code "$BASE/auth/me" -H "Authorization: Bearer $TMP_TOKEN")"
check "停用后无法再登录" "401" \
  "$(http_code -X POST "$BASE/auth/login" -H "$JSON" \
     -d "{\"username\":\"$TMP_USER\",\"password\":\"$TMP_PASS\"}")"

section "④ 自锁保护"

ADMIN_ID=$(curl -s "$BASE/auth/me" -H "$AUTH" | data_of "['id']")
check "不能停用当前登录的账号" "400" \
  "$(http_code -X PUT "$BASE/users/$ADMIN_ID" -H "$AUTH" -H "$JSON" \
     -d "{\"displayName\":\"陈立\",\"role\":\"ADMIN\",\"organizationId\":1,\"enabled\":false}")"
check "不能修改自己的角色" "400" \
  "$(http_code -X PUT "$BASE/users/$ADMIN_ID" -H "$AUTH" -H "$JSON" \
     -d "{\"displayName\":\"陈立\",\"role\":\"OPERATOR\",\"organizationId\":1,\"enabled\":true}")"
check "不能删除当前登录的账号" "400" \
  "$(http_code -X DELETE "$BASE/users/$ADMIN_ID" -H "$AUTH")"
check "改回自己（姓名与角色不变）-> 200" "200" \
  "$(http_code -X PUT "$BASE/users/$ADMIN_ID" -H "$AUTH" -H "$JSON" \
     -d "{\"displayName\":\"陈立\",\"role\":\"ADMIN\",\"organizationId\":1,\"jobTitle\":\"监测室主任\",\"phone\":\"138-0000-0001\",\"email\":\"chenli@example.com\",\"enabled\":true}")"

section "⑤ 收尾"

check "删除临时账号（逻辑删除）-> 200" "200" \
  "$(http_code -X DELETE "$BASE/users/$TMP_ID" -H "$AUTH")"
check "删除后不在列表里" "yes" \
  "$(curl -s "$BASE/users" -H "$AUTH" | python3 -c "
import sys, json
rows = json.load(sys.stdin)['data']
print('yes' if all(r['username'] != '$TMP_USER' for r in rows) else 'still-there')")"
TMP_ID=""   # 已经删了；清空以免 trap 再删一次（重复删会 404，虽然无害但会打印误导信息）

summary
