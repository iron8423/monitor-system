#!/bin/bash
# 验收 14：修改本人密码（个人中心）。
#
# 覆盖清单第 12 条一直缺的那颗牙：**递增 token_version 之后，旧令牌必须 401**。
# 那条此前只有源码级取证——「源码里写着」，没有一条端到端断言。本套件就是它。
#
# ---------------------------------------------------------------------------
# 为什么这个套件单独一个文件、且**必须排在最后**
#
# 改密会真实改掉一个账号的口令。这里用的是 admin（所有套件都靠它登录），而
# 「改回原口令」这件事在这套系统里**做不到**：新口令强制 ≥ 8 位，而演示口令是
# `123456`（6 位，见 DataInitializer）——这不是缺陷，是口令策略与演示种子之间
# 一个真实存在的落差；只是它决定了「轮换」只能发生在**一次性实例**上。
#
# 所以：
#   · 不带 `ALLOW_PASSWORD_ROTATION=1` 时，本套件只跑**不改状态**的三条（400 边界）；
#   · `run-all.sh --fresh` 会设置这个变量（那套 H2 库随进程消失，轮换无害），
#     并且本套件排在数组末尾——轮换之后没有别的套件还要登录。
#   · 对着 compose 那种**持久库**跑时不要设置它：admin 的口令会被真的改掉，
#     下一次谁都登不进来（这正是本套件排在最后、且默认不轮换的原因）。
# ---------------------------------------------------------------------------
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

TOKEN=$(login)
AUTH="Authorization: Bearer $TOKEN"

# 每次不同，且 ≥ 8 位；带上 RUN_ID 便于在日志里认出是哪一轮留下的
NEW_PASS="Tmp-${RUN_ID}-Pw1"

section "① 改密接口的边界（不改状态）"

BAD_BODY=$(curl -s -X POST "$BASE/auth/password" -H "$AUTH" -H "$JSON" \
  -d "{\"oldPassword\":\"definitely-not-the-password\",\"newPassword\":\"$NEW_PASS\"}")
check "原密码不符 -> 400" "400" \
  "$(http_code -X POST "$BASE/auth/password" -H "$AUTH" -H "$JSON" \
     -d "{\"oldPassword\":\"definitely-not-the-password\",\"newPassword\":\"$NEW_PASS\"}")"
check_contains "原因写明「原密码不正确」" "原密码不正确" \
  "$(printf '%s' "$BAD_BODY" | python3 -c "import sys,json;print(json.load(sys.stdin).get('message',''))")"

SHORT_BODY=$(curl -s -X POST "$BASE/auth/password" -H "$AUTH" -H "$JSON" \
  -d "{\"oldPassword\":\"$ADMIN_PASS\",\"newPassword\":\"short7x\"}")
check "新密码不足 8 位 -> 400" "400" \
  "$(http_code -X POST "$BASE/auth/password" -H "$AUTH" -H "$JSON" \
     -d "{\"oldPassword\":\"$ADMIN_PASS\",\"newPassword\":\"short7x\"}")"
check_contains "原因写明长度要求（不是笼统的『参数错误』）" "8" \
  "$(printf '%s' "$SHORT_BODY" | python3 -c "import sys,json;print(json.load(sys.stdin).get('message',''))")"
check "新旧密码相同 -> 400" "400" \
  "$(http_code -X POST "$BASE/auth/password" -H "$AUTH" -H "$JSON" \
     -d "{\"oldPassword\":\"$ADMIN_PASS\",\"newPassword\":\"$ADMIN_PASS\"}")"
check "被拒的请求没有改掉口令（原口令仍可登录）" "3" \
  "$(printf '%s' "$(login_as "$ADMIN_USER" "$ADMIN_PASS")" | awk -F. '{print NF}')"

if [ "${ALLOW_PASSWORD_ROTATION:-0}" != "1" ]; then
  section "② 真实轮换（已跳过）"
  info "未设置 ALLOW_PASSWORD_ROTATION=1：本实例可能是持久库，跳过会改掉口令的断言。"
  info "要在一次性实例上跑完整语义（新令牌可用 / 旧令牌 401 / 旧口令失效 / 审计留痕），"
  info "用 tools/acceptance/run-all.sh --fresh（它自己会设置这个变量）。"
  summary
fi

section "② 真实轮换（一次性实例）"
info "把 $ADMIN_USER 的口令轮换成一个 ≥8 位的临时口令；本实例跑完即弃，不回改"

NEW_TOKEN=$(curl -s -X POST "$BASE/auth/password" -H "$AUTH" -H "$JSON" \
  -d "{\"oldPassword\":\"$ADMIN_PASS\",\"newPassword\":\"$NEW_PASS\"}" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])" 2>/dev/null)
check "改密成功并返回新令牌（三段点分）" "3" "$(printf '%s' "$NEW_TOKEN" | awk -F. '{print NF}')"
check "新令牌可用（/auth/me -> 200）" "200" \
  "$(http_code "$BASE/auth/me" -H "Authorization: Bearer $NEW_TOKEN")"
check "旧令牌随即 401（令牌版本已递增，清单第 12 条的牙）" "401" \
  "$(http_code "$BASE/auth/me" -H "$AUTH")"
check "旧口令无法再登录" "401" \
  "$(http_code -X POST "$BASE/auth/login" -H "$JSON" \
     -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}")"
check "新口令可以登录（三段点分）" "3" \
  "$(printf '%s' "$(login_as "$ADMIN_USER" "$NEW_PASS")" | awk -F. '{print NF}')"

# 改了密码却查不到是谁改的，等于这条路径没有审计。用 python 取 action 字段而不是 grep 原文，
# 免得「报文恰好把中文转义了」造成假红。
check "审计里有「修改密码」这一条" "yes" \
  "$(curl -s "$BASE/audit-logs?pageNum=1&pageSize=20" -H "Authorization: Bearer $NEW_TOKEN" | python3 -c "
import sys, json
try:
    rows = json.load(sys.stdin)['data']['records']
except Exception:
    print('no'); raise SystemExit
print('yes' if any((r.get('action') or '') == '修改密码' for r in rows) else 'no')")"

# 个人资料字段随登录/改密响应一起回来（V17 的三个列 + 组织名）
check "改密响应里带个人资料字段（岗位/公司）" "yes" \
  "$(curl -s "$BASE/auth/me" -H "Authorization: Bearer $NEW_TOKEN" | python3 -c "
import sys, json
u = json.load(sys.stdin)['data']
print('yes' if 'jobTitle' in u and 'organizationName' in u and u.get('organizationName') else 'no')")"

summary
