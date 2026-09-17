#!/bin/bash
# 验收 15：账号体系（清单第 13 条）——注册 / 本人改资料 / 管理员看与删。
#
# 口径（2026-09-17 用户定案）：
#   · 个人信息归**本人**：注册时自己填，之后在个人中心自己改；管理员不参与。
#   · 管理员管**权限与存续**：看全部账号、改角色与启用、删账号（删除后本人需重新注册）。
#   · 注册不能选管理员；角色来自账号本身，不能「以别的角色登录」。
#
# 为什么进套件：账号是全系统唯一的「门」，改错一次就是把同事或自己关在门外；
# 而这些又全是写操作，人工点一遍很难覆盖边界。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

TOKEN=$(login)
AUTH="Authorization: Bearer $TOKEN"

TMP_USER="acc${RUN_ID//-/}"
TMP_PASS="Acc-${RUN_ID}-Pw1"     # ≥ 8 位
TMP_ID=""
TMP_TOKEN=""
# 注册时按公司名自动建的组织（改一次公司会再建一个）：套件结束必须删掉，
# 否则会污染依赖「组织数量」的套件——10-scope 就断言过「admin 比李敏多 1 个组织」，
# 实测被本套件留下的两个组织打红过。
TMP_ORGS=()

cleanup_user() {
  if [ -n "$TMP_ID" ]; then
    curl -s -o /dev/null -X DELETE "$BASE/users/$TMP_ID" -H "$AUTH" \
      && info "已删除临时账号 $TMP_USER（逻辑删除）"
  fi
  for org in "${TMP_ORGS[@]:-}"; do
    [ -n "$org" ] || continue
    curl -s -o /dev/null -X DELETE "$BASE/organizations/$org" -H "$AUTH" \
      && info "已删除临时组织 #$org"
  done
}
trap cleanup_user EXIT

section "① 用户列表：仅管理员、不外泄凭据"

check "非管理员访问 /users -> 403" "403" \
  "$(http_code "$BASE/users" -H "Authorization: Bearer $(login_as operator)")"
check "未带令牌 -> 401" "401" "$(http_code "$BASE/users")"

USERS=$(curl -s "$BASE/users" -H "$AUTH")
check "列表含 5 个种子账号（4 演示 + outsider）" "5" \
  "$(printf '%s' "$USERS" | python3 -c "
import sys, json
rows = json.load(sys.stdin)['data']
print(len([r for r in rows if r['username'] in {'admin','operator','analyst','maintainer','outsider'}]))")"
check "列表不含 password / tokenVersion 字段" "clean" \
  "$(printf '%s' "$USERS" | python3 -c "
import sys, json
rows = json.load(sys.stdin)['data']
leak = sorted({k for r in rows for k in r if k.lower() in ('password','tokenversion')})
print(','.join(leak) if leak else 'clean')")"
check "列表带出公司名（组织名，不是 id）" "清远电厂" \
  "$(printf '%s' "$USERS" | python3 -c "
import sys, json
rows = json.load(sys.stdin)['data']
print([r['organizationName'] for r in rows if r['username'] == 'admin'][0])")"

section "② 管理员既不能建号，也改不到别人的资料"

# 建号只能走自助注册：管理端没有 POST /users 了（405 = 方法不允许）
check "管理员 POST /users（建号）-> 405" "405" \
  "$(http_code -X POST "$BASE/users" -H "$AUTH" -H "$JSON" \
     -d "{\"username\":\"$TMP_USER\",\"password\":\"$TMP_PASS\",\"displayName\":\"临时\",\"role\":\"OPERATOR\"}")"

section "③ 自助注册：规则与边界"

check "注册：密码不足 8 位 -> 400" "400" \
  "$(http_code -X POST "$BASE/auth/register" -H "$JSON" \
     -d "{\"username\":\"$TMP_USER\",\"password\":\"short7x\",\"displayName\":\"临时\",\"role\":\"OPERATOR\"}")"
check "注册：不能选管理员角色 -> 400" "400" \
  "$(http_code -X POST "$BASE/auth/register" -H "$JSON" \
     -d "{\"username\":\"$TMP_USER\",\"password\":\"$TMP_PASS\",\"displayName\":\"临时\",\"role\":\"ADMIN\"}")"
check "注册：重名（admin）-> 400" "400" \
  "$(http_code -X POST "$BASE/auth/register" -H "$JSON" \
     -d "{\"username\":\"admin\",\"password\":\"$TMP_PASS\",\"displayName\":\"重名\",\"role\":\"OPERATOR\"}")"
check "注册：账号格式非法 -> 400" "400" \
  "$(http_code -X POST "$BASE/auth/register" -H "$JSON" \
     -d "{\"username\":\"a b\",\"password\":\"$TMP_PASS\",\"displayName\":\"临时\",\"role\":\"OPERATOR\"}")"

REG=$(curl -s -X POST "$BASE/auth/register" -H "$JSON" \
  -d "{\"username\":\"$TMP_USER\",\"password\":\"$TMP_PASS\",\"displayName\":\"验收临时账号\",\"company\":\"验收公司\",\"jobTitle\":\"值班员\",\"phone\":\"138-0000-9999\",\"email\":\"tmp@example.com\",\"role\":\"OPERATOR\"}")
TMP_ID=$(printf '%s' "$REG" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['user']['id'])" 2>/dev/null)
TMP_TOKEN=$(printf '%s' "$REG" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])" 2>/dev/null)
check "注册成功并直接拿到令牌（注册即登录）" "3" "$(printf '%s' "$TMP_TOKEN" | awk -F. '{print NF}')"
check "注册者角色是所选的非管理员角色" "OPERATOR" \
  "$(curl -s "$BASE/auth/me" -H "Authorization: Bearer $TMP_TOKEN" | data_of "['role']")"
check "注册时填的公司自动落成组织" "验收公司" \
  "$(curl -s "$BASE/auth/me" -H "Authorization: Bearer $TMP_TOKEN" | data_of "['organizationName']")"
TMP_ORGS+=("$(curl -s "$BASE/auth/me" -H "Authorization: Bearer $TMP_TOKEN" | data_of "['organizationId']")")
check "注册后的普通账号访问 /users 仍 403" "403" \
  "$(http_code "$BASE/users" -H "Authorization: Bearer $TMP_TOKEN")"

section "④ 个人信息由本人维护"

check "本人改资料 -> 200" "200" \
  "$(http_code -X PUT "$BASE/auth/me" -H "Authorization: Bearer $TMP_TOKEN" -H "$JSON" \
     -d '{"displayName":"验收临时账号（改）","company":"验收公司（改）","jobTitle":"值班员（改）","phone":"138-0000-8888","email":"tmp2@example.com"}')"
check "改完读回一致（姓名/岗位/电话/公司）" "验收临时账号（改）|值班员（改）|138-0000-8888|验收公司（改）" \
  "$(curl -s "$BASE/auth/me" -H "Authorization: Bearer $TMP_TOKEN" | python3 -c "
import sys, json
d = json.load(sys.stdin)['data']
print('|'.join([d.get('displayName') or '', d.get('jobTitle') or '', d.get('phone') or '', d.get('organizationName') or '']))")"
TMP_ORGS+=("$(curl -s "$BASE/auth/me" -H "Authorization: Bearer $TMP_TOKEN" | data_of "['organizationId']")")
# 本人改不了自己的角色与启用状态：这两个字段不在 PUT /auth/me 的入参里，
# 多塞进来也会被忽略（DTO 只有个人信息那五个字段）
check "本人改不了自己的角色（塞 role 也不生效）" "OPERATOR" \
  "$(curl -s -X PUT "$BASE/auth/me" -H "Authorization: Bearer $TMP_TOKEN" -H "$JSON" \
     -d '{"displayName":"验收临时账号（改）","role":"ADMIN","enabled":false}' >/dev/null; \
     curl -s "$BASE/auth/me" -H "Authorization: Bearer $TMP_TOKEN" | data_of "['role']")"

section "⑤ 管理员改的是权限，不是资料"

check "管理员改角色 -> 200" "200" \
  "$(http_code -X PUT "$BASE/users/$TMP_ID" -H "$AUTH" -H "$JSON" -d '{"role":"ANALYST","enabled":true}')"
check "角色已生效" "ANALYST" \
  "$(curl -s "$BASE/users/$TMP_ID" -H "$AUTH" | data_of "['role']")"
# 关键：管理员**改不到**个人信息——即便请求里塞满那些字段，服务端也只写 role/enabled
curl -s -o /dev/null -X PUT "$BASE/users/$TMP_ID" -H "$AUTH" -H "$JSON" \
  -d '{"role":"ANALYST","enabled":true,"displayName":"被管理员改的","phone":"000","jobTitle":"改","email":"x@y.com"}'
check "管理员塞资料字段也不生效（姓名/电话/岗位保持本人所填）" "验收临时账号（改）|138-0000-8888|值班员（改）" \
  "$(curl -s "$BASE/users/$TMP_ID" -H "$AUTH" | python3 -c "
import sys, json
d = json.load(sys.stdin)['data']
print('|'.join([d.get('displayName') or '', d.get('phone') or '', d.get('jobTitle') or '']))")"

check "管理员停用该账号 -> 200" "200" \
  "$(http_code -X PUT "$BASE/users/$TMP_ID" -H "$AUTH" -H "$JSON" -d '{"role":"ANALYST","enabled":false}')"
# 停用必须**立刻**生效（鉴权每请求回库核对 enabled），不用等令牌寿命走完
check "停用后旧令牌随即 401" "401" \
  "$(http_code "$BASE/auth/me" -H "Authorization: Bearer $TMP_TOKEN")"
check "停用后无法再登录" "401" \
  "$(http_code -X POST "$BASE/auth/login" -H "$JSON" -d "{\"username\":\"$TMP_USER\",\"password\":\"$TMP_PASS\"}")"

section "⑥ 删除后账号失效，且同名可以重新注册"

check "管理员删除该账号 -> 200" "200" "$(http_code -X DELETE "$BASE/users/$TMP_ID" -H "$AUTH")"
check "删除后无法登录" "401" \
  "$(http_code -X POST "$BASE/auth/login" -H "$JSON" -d "{\"username\":\"$TMP_USER\",\"password\":\"$TMP_PASS\"}")"
check "列表里已看不到它" "gone" \
  "$(curl -s "$BASE/users" -H "$AUTH" | python3 -c "
import sys, json
rows = json.load(sys.stdin)['data']
print('gone' if all(r['username'] != '$TMP_USER' for r in rows) else 'still-there')")"
# 逻辑删除的行还在库里，占着 username 的唯一索引——所以这条必须验：本人能重新注册同名账号
REREG=$(curl -s -X POST "$BASE/auth/register" -H "$JSON" \
  -d "{\"username\":\"$TMP_USER\",\"password\":\"$TMP_PASS\",\"displayName\":\"重新注册\",\"role\":\"MAINTAINER\"}")
TMP_ID=$(printf '%s' "$REREG" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['user']['id'])" 2>/dev/null)
check "同名重新注册成功（复用已删除的那行）" "yes" "$([ -n "$TMP_ID" ] && echo yes || echo no)"
check "重新注册后能用新资料登录" "MAINTAINER" \
  "$(curl -s "$BASE/auth/me" -H "Authorization: Bearer $(login_as "$TMP_USER" "$TMP_PASS")" | data_of "['role']")"

section "⑦ 自锁保护"

ADMIN_ID=$(curl -s "$BASE/auth/me" -H "$AUTH" | data_of "['id']")
check "不能停用当前登录的账号" "400" \
  "$(http_code -X PUT "$BASE/users/$ADMIN_ID" -H "$AUTH" -H "$JSON" -d '{"role":"ADMIN","enabled":false}')"
check "不能修改自己的角色" "400" \
  "$(http_code -X PUT "$BASE/users/$ADMIN_ID" -H "$AUTH" -H "$JSON" -d '{"role":"OPERATOR","enabled":true}')"
check "不能删除当前登录的账号" "400" \
  "$(http_code -X DELETE "$BASE/users/$ADMIN_ID" -H "$AUTH")"

section "⑧ 收尾"

check "删除临时账号 -> 200" "200" "$(http_code -X DELETE "$BASE/users/$TMP_ID" -H "$AUTH")"
TMP_ID=""   # 已经删了，别让 trap 再删一次

summary
