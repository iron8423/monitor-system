#!/bin/bash
# 验收 18：登录失败限流（复查清单 P1-14 的一半）。
#
#   改造前 /auth/login 没有任何节流：脚本可以按每秒几千次猜口令，平台既不拦也不记。
#   现在的口径：同一「来源 IP + 账号」在 300 秒内失败 5 次即 429，成功即清零，窗口过后自动放行。
#
#   为什么用**临时账号**而不是拿 admin 打：限流键包含账号，打 admin 会把 admin 锁 5 分钟，
#   于是同一轮里排在后面的套件（14 改口令等）全部登录不上——那会把一次"功能正常"变成
#   一串看起来毫不相关的失败。临时账号用完即删，效果一样、影响面为零。
#
#   本套件同时钉住三条容易写错的地方：
#     ① 阈值内失败是 401（凭据不对），达阈值才是 429（别再试了）——两种码语义不同；
#     ② 成功登录**清零**计数（否则正常用户敲错两次就一路背着记录）；
#     ③ 按「IP + 账号」而不是只按 IP：另一个账号在同一台机器上照样能登录。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

TOKEN=$(login)
AUTH="Authorization: Bearer $TOKEN"

TMP_USER="lim${RUN_ID//-/}"          # 账号规则：[A-Za-z0-9_.-]{3,32}
TMP_USER="${TMP_USER:0:24}"
TMP_PASS="Limit-2026-$RUN_ID"

section "⓪ 夹具：注册一个临时账号（注册即登录，顺带证明它可用）"
REG=$(curl -s -X POST "$BASE/auth/register" -H "$JSON" \
  -d "{\"username\":\"$TMP_USER\",\"password\":\"$TMP_PASS\",\"displayName\":\"限流验收临时账号\",\"role\":\"ANALYST\"}")
TMP_ID=$(printf '%s' "$REG" | data_of "['user']['id']")
check "临时账号注册成功" "true" "$([ -n "$TMP_ID" ] && [ "$TMP_ID" != "None" ] && echo true || echo false)"
info "username=$TMP_USER id=$TMP_ID"

bad_login() {
  curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/auth/login" -H "$JSON" \
    -d "{\"username\":\"$TMP_USER\",\"password\":\"definitely-wrong-$1\"}"
}

section "① 阈值内：每次失败都是 401（凭据不对，不是"别再试了"）"
for i in 1 2 3 4; do
  check "第 $i 次错口令 -> 401" "401" "$(bad_login "$i")"
done

section "② 达到阈值（第 5 次）后：429 + 写明要等多久"
# 第 5 次仍是 401（这一次才把计数推到阈值），第 6 次起 429
check "第 5 次错口令 -> 401（阈值边界属于"还能试"）" "401" "$(bad_login 5)"
LOCKED=$(curl -s -X POST "$BASE/auth/login" -H "$JSON" \
  -d "{\"username\":\"$TMP_USER\",\"password\":\"definitely-wrong-6\"}")
check "第 6 次 -> 429" "429" \
  "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/auth/login" -H "$JSON" \
     -d "{\"username\":\"$TMP_USER\",\"password\":\"definitely-wrong-6\"}")"
# 注意不能走 lib.sh 的 data_of：它先取 data 再下标，而错误响应的 data 是 null，
# 结果会得到占位串"<非 JSON 或结构不符>"——断言看起来在测文案，其实永远测不到。
check_contains "429 的文案里给出了还要等多久" "秒后再试" \
  "$(printf '%s' "$LOCKED" | python3 -c "import sys,json;print(json.load(sys.stdin).get('message'))")"

section "③ 锁定期内**正确**口令也进不去（否则爆破者只需要最后试对一次）"
check "锁定期间用正确口令 -> 仍是 429" "429" \
  "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/auth/login" -H "$JSON" \
     -d "{\"username\":\"$TMP_USER\",\"password\":\"$TMP_PASS\"}")"

section "④ 按键是「IP + 账号」：同一台机器上别的账号不受影响"
check "admin 仍能正常登录（没有被 IP 连坐）" "3" \
  "$(curl -s -X POST "$BASE/auth/login" -H "$JSON" -d '{"username":"admin","password":"123456"}' \
     | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']['token'].split('.')))")"

section "⑤ 成功登录清零：另建一个账号，错 3 次后登录成功，再错 3 次仍应只是 401"
TMP2="lim2${RUN_ID//-/}"
TMP2="${TMP2:0:24}"
curl -s -o /dev/null -X POST "$BASE/auth/register" -H "$JSON" \
  -d "{\"username\":\"$TMP2\",\"password\":\"$TMP_PASS\",\"displayName\":\"限流验收临时账号2\",\"role\":\"ANALYST\"}"
for i in 1 2 3; do
  curl -s -o /dev/null -X POST "$BASE/auth/login" -H "$JSON" \
    -d "{\"username\":\"$TMP2\",\"password\":\"wrong-$i\"}"
done
check "错 3 次后用正确口令 -> 200 且拿到令牌" "3" \
  "$(curl -s -X POST "$BASE/auth/login" -H "$JSON" -d "{\"username\":\"$TMP2\",\"password\":\"$TMP_PASS\"}" \
     | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']['token'].split('.')))")"
for i in 4 5 6; do
  curl -s -o /dev/null -X POST "$BASE/auth/login" -H "$JSON" \
    -d "{\"username\":\"$TMP2\",\"password\":\"wrong-$i\"}"
done
check "清零后再错 3 次 -> 仍是 401（计数确实从 0 重来）" "401" \
  "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/auth/login" -H "$JSON" \
     -d "{\"username\":\"$TMP2\",\"password\":\"wrong-7\"}")"

section "⑥ 回收临时账号（限流记录随进程内存，无需清理）"
for u in "$TMP_USER" "$TMP2"; do
  UID_=$(curl -s "$BASE/users" -H "$AUTH" | python3 -c "
import sys, json
rows = json.load(sys.stdin)['data']
hit = [r for r in rows if r.get('username') == '$u']
print(hit[0]['id'] if hit else '')
")
  if [ -n "$UID_" ]; then
    check "临时账号 $u 已删除" "200" "$(http_code -X DELETE "$BASE/users/$UID_" -H "$AUTH")"
  else
    info "没在用户列表里找到 $u（可能已被逻辑删除），跳过"
  fi
done

summary
