#!/bin/bash
# 验收 10：项目数据范围隔离（验收第 7 条前半）。
#
# 种子（V8 + ProjectMemberInitializer）：
#   组织 1 清远电厂 → 项目 1 PRJ-QY-HK（7 测点）—— 四个演示账号都在里面
#   组织 2 西江水泥 → 项目 2 PRJ-XJ-CKQ（2 测点）—— 只有 admin 在里面
#   账号 outsider：不在任何项目里（对照组）
#
# 两条写法上的硬要求：
#
# 1) **每一条「看不到」都要配一条「看得到」作对照。** 只断言 403 的话，
#    一个把所有人都拒掉的实现会让整页断言全绿——那种绿比红更糟。
#    所以 ① 钉住管理员的全量视角，④ 钉住非管理员对自己项目的正常读取，
#    ⑦ 用「同一个角色、同一个动作、只有警情归属不同」来排除角色校验的干扰。
#
# 2) **跨用户的条数比较用差值，不用绝对值。** 本套件跑在其它套件之后，
#    若前面哪个套件中途失败留下了临时数据，绝对值断言会在这里连带转红，
#    把「隔离坏了」和「库脏了」混成一件事。差值（admin − 李敏 == 项目 2 的规模）
#    只依赖两边看到的是不是同一个库，与库里有多少无关。
#    例外是 outsider（一切皆 0，与残留无关）与 ⑪ 的基线回归。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

# login 打印的是**裸 token**（本仓约定：TOKEN=$(login) 后再自己拼 Bearer）。
# 写成 AUTH=$(login) 的话头是坏的，admin 的每条请求都 401 —— 而 401 的响应体没有 data 键，
# 于是下游 helper 一律回 marker，marker 与 marker 相比还会「通过」。
# 这个套件第一次跑就是这么红的：50 条失败里混着几条假绿，根因是一个字符串拼接。
TOKEN=$(login)                                           # admin（全量视角）
AUTH="Authorization: Bearer $TOKEN"
OP_TOKEN=$(login_as operator)                            # 李敏：在项目 1（⑪ 订阅 SSE 要裸 token）
OP_AUTH="Authorization: Bearer $OP_TOKEN"
OUT_TOKEN=$(login_as outsider)                           # 无项目（对照组）
OUT_AUTH="Authorization: Bearer $OUT_TOKEN"

# 前提自检：三个身份都必须真的能用，否则后面是「红成一片」而不是「隔离坏了」——
# 两者在输出上长得一样，必须在这里分开。退出码 2 = 环境问题（同 lib.sh 的约定）。
for pair in "admin:$AUTH" "operator:$OP_AUTH" "outsider:$OUT_AUTH"; do
  who="${pair%%:*}"; hdr="${pair#*:}"
  [ -n "${hdr#Authorization: Bearer }" ] || {
    printf '%s%s 登录失败（口令或账号不对）%s\n' "$C_RED" "$who" "$C_OFF" >&2; exit 2; }
  [ "$(curl -s "$BASE/auth/me" -H "$hdr" | code_of)" = "0" ] || {
    printf '%s%s 的令牌被拒，后续断言无意义，中止%s\n' "$C_RED" "$who" "$C_OFF" >&2; exit 2; }
done

# 列表长度（data 是数组）或分页 total（data 是对象）
count_of() { python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)['data']
    print(len(d) if isinstance(d, list) else d.get('total'))
except Exception:
    print('<非 JSON 或结构不符>')"; }

# 列表里是否出现某个 id（用 id 而不是名字：名字可能重复，id 不会）
has_id() { python3 -c "
import sys, json
try:
    rs = json.load(sys.stdin)['data']
    rs = rs if isinstance(rs, list) else rs.get('records', [])
    print(str(any(str(r.get('id')) == '$1' for r in rs)).lower())
except Exception:
    print('<非 JSON 或结构不符>')"; }

# total 是否 > 0（布尔，用来断言「查得到 / 查不到」）
any_of() { python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)['data']
    n = len(d) if isinstance(d, list) else d.get('total')
    print(str(n > 0).lower())
except Exception:
    print('<非 JSON 或结构不符>')"; }

# 是不是一个正常的正整数 id。**专治「非空即真」**：[ -n "$X" ] 对错误标记也是真的，
# 于是「两个临时测点都建出来了」在一整片 401 里照样显示通过。
is_id() { case "$1" in ''|*[!0-9]*) echo false ;; *) echo true ;; esac; }

# 取列表里 code / name 等于给定值的那条记录的 id（取不到打印空串）
id_of_code() { python3 -c "
import sys, json
try:
    print(next((x['id'] for x in json.load(sys.stdin)['data'] if x['code'] == '$1'), ''))
except Exception:
    print('')"; }

id_of_name() { python3 -c "
import sys, json
try:
    print(next((x['id'] for x in json.load(sys.stdin)['data'] if x['name'] == '$1'), ''))
except Exception:
    print('')"; }

section "⓪ 前提：隔离的双方都得真的存在"
# 这一节防的是「断言空洞」——项目 2 不存在的话，下面所有「看不到项目 2」都是废话。
PRJ_B=$(curl -s "$BASE/projects" -H "$AUTH" | id_of_code "PRJ-XJ-CKQ")
ORG_B=$(curl -s "$BASE/organizations" -H "$AUTH" | id_of_code "ORG-XJ")
PT_B=$(curl -s "$BASE/points" -H "$AUTH" | id_of_code "P-CKQ01")
# 对象与场景没有 code 列，按名字取（名字在 V8 里是字面量；不用「列表最后一条」这种取法——
# 前面哪个套件留下一个对象，最后一条就不是项目 2 的了）
OBJ_B=$(curl -s "$BASE/objects" -H "$AUTH" | id_of_name "采空区地表")
SCN_B=$(curl -s "$BASE/scenes" -H "$AUTH" | id_of_name "采空区")
check "项目 2（PRJ-XJ-CKQ）存在" "true" "$([ -n "$PRJ_B" ] && echo true || echo false)"
check "组织 2（ORG-XJ）存在" "true" "$([ -n "$ORG_B" ] && echo true || echo false)"
# 项目 2 的测点必须真的有：否则「看不到」只是「看不见一个空列表」
check "项目 2 的测点 P-CKQ01 存在" "true" "$([ -n "$PT_B" ] && echo true || echo false)"
check "项目 2 的对象与场景都存在" "true" \
  "$([ -n "$OBJ_B" ] && [ -n "$SCN_B" ] && echo true || echo false)"
# 李敏确实在项目 1 里——不在的话下面就不是「隔离生效」而是「谁都没有」
check "李敏能看到项目 1" "true" "$(curl -s "$BASE/projects" -H "$OP_AUTH" | has_id 1)"
check "李敏能读种子测点 1 的档案" "200" "$(http_code "$BASE/points/1" -H "$OP_AUTH")"
info "项目 2 id=$PRJ_B 组织 2 id=$ORG_B 测点 P-CKQ01 id=$PT_B 对象 id=$OBJ_B 场景 id=$SCN_B"

# 基线：⑪ 要按这几个数回归（套件自己不留垃圾）
ALL_P0=$(curl -s "$BASE/points" -H "$AUTH" | count_of)
OP_P0=$(curl -s "$BASE/points" -H "$OP_AUTH" | count_of)
ALL_PRJ0=$(curl -s "$BASE/projects" -H "$AUTH" | count_of)
OP_D0=$(curl -s "$BASE/devices" -H "$OP_AUTH" | count_of)

section "① 对照组：管理员是全量视角（没有它，下面全是空的）"
check "admin 可直达项目 2" "200" "$(http_code "$BASE/projects/$PRJ_B" -H "$AUTH")"
check "admin 可直达项目 2 的概览" "200" "$(http_code "$BASE/projects/$PRJ_B/summary" -H "$AUTH")"
check "admin 可直达项目 2 的测点" "200" "$(http_code "$BASE/points/$PT_B" -H "$AUTH")"
check "admin 可直达项目 2 测点的 latest" "200" "$(http_code "$BASE/points/$PT_B/latest" -H "$AUTH")"
check "admin 的项目列表含项目 2" "true" "$(curl -s "$BASE/projects" -H "$AUTH" | has_id "$PRJ_B")"
check "admin 的测点列表含 P-CKQ01" "true" "$(curl -s "$BASE/points" -H "$AUTH" | has_id "$PT_B")"
check "admin 看得到组织 2" "true" "$(curl -s "$BASE/organizations" -H "$AUTH" | has_id "$ORG_B")"
check "admin 的项目数比李敏多 1" "1" "$((ALL_PRJ0 - $(curl -s "$BASE/projects" -H "$OP_AUTH" | count_of)))"
# 项目 2 有 1 场景 / 1 对象 / 2 测点 / 4 测项 —— 差值就是它，多一个少一个都说明链子算错了
diff_of() { echo "$(($(curl -s "$BASE/$1" -H "$AUTH" | count_of) - $(curl -s "$BASE/$1" -H "$OP_AUTH" | count_of)))"; }
check "admin 比李敏多 1 个场景" "1" "$(diff_of scenes)"
check "admin 比李敏多 1 个对象" "1" "$(diff_of objects)"
check "admin 比李敏多 2 个测点" "2" "$(diff_of points)"
check "admin 比李敏多 4 个测项（2 点 × 2 项）" "4" "$(diff_of metrics)"
check "admin 比李敏多 1 个组织" "1" "$(diff_of organizations)"

section "② 非管理员：列表里就没有别人项目的东西"
check "李敏的项目列表不含项目 2" "false" "$(curl -s "$BASE/projects" -H "$OP_AUTH" | has_id "$PRJ_B")"
check "李敏的测点列表不含 P-CKQ01" "false" "$(curl -s "$BASE/points" -H "$OP_AUTH" | has_id "$PT_B")"
check "李敏的对象列表不含项目 2 的对象" "false" "$(curl -s "$BASE/objects" -H "$OP_AUTH" | has_id "$OBJ_B")"
check "李敏的组织列表不含西江水泥" "false" "$(curl -s "$BASE/organizations" -H "$OP_AUTH" | has_id "$ORG_B")"
# 分页端点与列表端点是两条实现路径：只覆盖 list() 的话 /page 会整表吐出来
check "/points/page 与 /points 同口径（差值仍是 2）" "2" \
  "$(( $(curl -s "$BASE/points/page?pageSize=100" -H "$AUTH" | count_of) \
     - $(curl -s "$BASE/points/page?pageSize=100" -H "$OP_AUTH" | count_of) ))"

section "③ 非管理员直达不可见资源 -> 403（不是 404、也不是 200）"
# 403 而不是 404 是定过的口径：记录确实存在，只是不在你的范围内。
# 演示时要的是「看得见边界」，404 会被误当成「数据没了」。
check "直达项目 2" "403" "$(http_code "$BASE/projects/$PRJ_B" -H "$OP_AUTH")"
check "直达项目 2 的概览" "403" "$(http_code "$BASE/projects/$PRJ_B/summary" -H "$OP_AUTH")"
check "直达项目 2 的测点档案" "403" "$(http_code "$BASE/points/$PT_B" -H "$OP_AUTH")"
check "直达项目 2 测点的 latest" "403" "$(http_code "$BASE/points/$PT_B/latest" -H "$OP_AUTH")"
check "直达项目 2 测点的 series" "403" "$(http_code "$BASE/points/$PT_B/series" -H "$OP_AUTH")"
check "直达项目 2 测点的影像列表" "403" "$(http_code "$BASE/points/$PT_B/media" -H "$OP_AUTH")"
check "直达项目 2 的对象" "403" "$(http_code "$BASE/objects/$OBJ_B" -H "$OP_AUTH")"
check "直达项目 2 的场景" "403" "$(http_code "$BASE/scenes/$SCN_B" -H "$OP_AUTH")"
check "直达组织 2（西江水泥）" "403" "$(http_code "$BASE/organizations/$ORG_B" -H "$OP_AUTH")"

section "④ 非管理员对自己项目照常可读（防止「全 403」也算绿）"
check "项目 1 详情" "200" "$(http_code "$BASE/projects/1" -H "$OP_AUTH")"
check "测点 1 档案" "200" "$(http_code "$BASE/points/1" -H "$OP_AUTH")"
check "测点 1 latest" "200" "$(http_code "$BASE/points/1/latest" -H "$OP_AUTH")"
check "测点 1 series" "200" "$(http_code "$BASE/points/1/series" -H "$OP_AUTH")"
# 概览的 pointCount 是沿 project→scene→object→point 走出来的，与 /points 的范围条件
# 是两条独立实现。让它们互相对账（而不是钉一个绝对值 7）：既验了范围口径一致，
# 又不会被前面套件万一没回收干净的临时点带红。
OP_PTS=$(curl -s "$BASE/points" -H "$OP_AUTH" | count_of)
check "项目 1 概览的测点数 == 李敏可见的测点数（两条路径同口径）" "$OP_PTS" \
  "$(curl -s "$BASE/projects/1/summary" -H "$OP_AUTH" | data_of "['pointCount']")"
info "李敏可见测点数=$OP_PTS（种子 7 + 前面套件可能留下的临时点）"
check "项目 1 概览与 admin 同口径" \
  "$(curl -s "$BASE/projects/1/summary" -H "$AUTH" | data_of "['pointCount']")" \
  "$(curl -s "$BASE/projects/1/summary" -H "$OP_AUTH" | data_of "['pointCount']")"
check "对象 1（项目 1 的）" "200" "$(http_code "$BASE/objects/1" -H "$OP_AUTH")"
check "组织 1（清远电厂）" "200" "$(http_code "$BASE/organizations/1" -H "$OP_AUTH")"
# radar-001 绑了项目 1 的全部 7 个测点，故对李敏可见
check "李敏可读 radar-001 状态" "200" "$(http_code "$BASE/devices/1/status" -H "$OP_AUTH")"
check "李敏的设备数与基线一致（没有凭空多出设备）" "$OP_D0" \
  "$(curl -s "$BASE/devices" -H "$OP_AUTH" | count_of)"
# 404 与 403 必须分得开：不存在的记录仍是 404
check "不存在的测点仍是 404" "404" "$(http_code "$BASE/points/99999" -H "$OP_AUTH")"
check "不存在的项目仍是 404" "404" "$(http_code "$BASE/projects/99999" -H "$OP_AUTH")"

section "⑤ 无成员关系的账号：什么都看不到（fail-closed，不是 fail-open）"
# 全套里最要紧的负向断言。MyBatis-Plus 的 in(空集合) 会把条件整条丢掉，
# 于是「你没有可见项目」会退化成「所有项目都可见」——而且**没有任何报错**。
# outsider 存在的唯一目的就是让这条路径可测：推理在这里不可靠，
# 写 inIds 的人当然觉得自己写对了。
check "outsider 项目数" "0" "$(curl -s "$BASE/projects" -H "$OUT_AUTH" | count_of)"
check "outsider 测点数" "0" "$(curl -s "$BASE/points" -H "$OUT_AUTH" | count_of)"
check "outsider 场景数" "0" "$(curl -s "$BASE/scenes" -H "$OUT_AUTH" | count_of)"
check "outsider 对象数" "0" "$(curl -s "$BASE/objects" -H "$OUT_AUTH" | count_of)"
check "outsider 测项数" "0" "$(curl -s "$BASE/metrics" -H "$OUT_AUTH" | count_of)"
check "outsider 设备数" "0" "$(curl -s "$BASE/devices" -H "$OUT_AUTH" | count_of)"
check "outsider 组织数" "0" "$(curl -s "$BASE/organizations" -H "$OUT_AUTH" | count_of)"
check "outsider 警情数" "0" "$(curl -s "$BASE/alarms" -H "$OUT_AUTH" | count_of)"
check "outsider 维护记录数" "0" "$(curl -s "$BASE/maintenance-records" -H "$OUT_AUTH" | count_of)"
check "outsider /points/page 也是 0" "0" \
  "$(curl -s "$BASE/points/page?pageSize=100" -H "$OUT_AUTH" | count_of)"
check "outsider 直达种子项目 1 -> 403" "403" "$(http_code "$BASE/projects/1" -H "$OUT_AUTH")"
check "outsider 直达种子测点 1 的 latest -> 403" "403" \
  "$(http_code "$BASE/points/1/latest" -H "$OUT_AUTH")"
check "outsider 直达种子设备 1 的状态 -> 403" "403" \
  "$(http_code "$BASE/devices/1/status" -H "$OUT_AUTH")"
# 刻意的例外：全局告警规则（point_id 为空）对所有登录用户可见。
# 规则本体不含项目业务数据（只有阈值与等级），而「为什么报警」不该对看得见警情的人保密。
check "outsider 仍看得到全局告警规则（例外）" "true" \
  "$(curl -s "$BASE/alarm-rules" -H "$OUT_AUTH" | any_of)"

section "⑥ 警情：两条来源都要滤（B-14 的镜像）"
# B-14 是「设备告警一条也数不进来」（少算），这里是反向的「一条都不该多给」。
# 造数一律落在**本次新建的临时测点**上（本仓既有约定，见 README 的设计说明）：
# 一个挂在项目 1 的对象下，一个挂在项目 2 的对象下。
CODE1="P-SCP1-$RUN_ID"; CODE2="P-SCP2-$RUN_ID"
P1=$(curl -s -X POST "$BASE/points" -H "$AUTH" -H "$JSON" \
     -d "{\"objectId\":1,\"code\":\"$CODE1\",\"name\":\"隔离验收临时点A\",\"type\":\"POINT_DEFORMATION\",\"enabled\":true}" \
     | data_of "['id']")
P2=$(curl -s -X POST "$BASE/points" -H "$AUTH" -H "$JSON" \
     -d "{\"objectId\":$OBJ_B,\"code\":\"$CODE2\",\"name\":\"隔离验收临时点B\",\"type\":\"POINT_DEFORMATION\",\"enabled\":true}" \
     | data_of "['id']")
check "两个临时测点都建出来了" "true" \
  "$([ "$(is_id "$P1")" = true ] && [ "$(is_id "$P2")" = true ] && echo true || echo false)"
info "临时测点：项目 1 -> $P1（$CODE1），项目 2 -> $P2（$CODE2）"

# 各灌一条超限值（全局规则 defo_mm >= 3mm，V2 种子），各自产生一条待确认警情。
# 幂等键含 messageId，故两个点的 messageId 必须不同——否则第二批整批判 DUPLICATE，
# 而重复上报照样返回 HTTP 200，于是「警情没产生」会被误判成规则不生效。
#
# 两条都用 radar-001 上报（ingest_id 的默认设备）：上报只校验点号与设备**存在**，
# 不校验绑定关系。这不是图省事——正因如此，这两条警情的 point_id 才有值而 device_id 为空，
# 于是下面验的确实是「警情按点归属过滤」，而不是被设备那条支路顺手带过。
T=$(python3 -c "import datetime;print(datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).replace(microsecond=0).isoformat())")
ingest_id "scp1-$RUN_ID" "$CODE1" "$T" '"defo_mm":4.6' >/dev/null
ingest_id "scp2-$RUN_ID" "$CODE2" "$T" '"defo_mm":4.6' >/dev/null
sleep 1

check "项目 1 临时点上有 1 条待确认警情" "1" \
  "$(curl -s "$BASE/alarms?pointId=$P1&status=PENDING" -H "$AUTH" | data_of "['total']")"
check "项目 2 临时点上有 1 条待确认警情" "1" \
  "$(curl -s "$BASE/alarms?pointId=$P2&status=PENDING" -H "$AUTH" | data_of "['total']")"
ALARM_A=$(curl -s "$BASE/alarms?pointId=$P1&status=PENDING" -H "$AUTH" | python3 -c "
import sys,json;print(json.load(sys.stdin)['data']['records'][0]['id'])")
ALARM_B=$(curl -s "$BASE/alarms?pointId=$P2&status=PENDING" -H "$AUTH" | python3 -c "
import sys,json;print(json.load(sys.stdin)['data']['records'][0]['id'])")
info "警情 id：项目 1 -> $ALARM_A，项目 2 -> $ALARM_B"

check "李敏看得到自己项目的警情" "true" \
  "$(curl -s "$BASE/alarms?pointId=$P1" -H "$OP_AUTH" | any_of)"
check "李敏按 pointId 查项目 2 的警情：查不到" "false" \
  "$(curl -s "$BASE/alarms?pointId=$P2" -H "$OP_AUTH" | any_of)"
check "李敏的警情列表里不含项目 2 那条" "false" \
  "$(curl -s "$BASE/alarms?pageSize=200" -H "$OP_AUTH" | has_id "$ALARM_B")"
check "李敏直达项目 2 的警情详情 -> 403" "403" \
  "$(http_code "$BASE/alarms/$ALARM_B" -H "$OP_AUTH")"

section "⑦ 处置也要过范围（只滤列表的话仍能改别人项目的警情）"
# 同一个角色、同一个动作，只有「警情属于谁」不同——这样 403 就不可能来自角色校验
# （AlarmConstants 里 OPERATOR 是允许 confirm 的，04 套件已单独验过角色矩阵）。
ACT='{"action":"confirm","comment":"验收：隔离用例"}'
check "李敏能确认自己项目的警情 -> 200" "200" \
  "$(http_code -X POST "$BASE/alarms/$ALARM_A/actions" -H "$OP_AUTH" -H "$JSON" -d "$ACT")"
check "李敏不能确认项目 2 的警情 -> 403" "403" \
  "$(http_code -X POST "$BASE/alarms/$ALARM_B/actions" -H "$OP_AUTH" -H "$JSON" -d "$ACT")"
# 两侧都要查状态：只看 403 的话，一个「先改状态再报 403」的实现也能绿。
# 事件顺序也不能反——反了就变成「先确认，再看它是不是还没确认」。
check "项目 1 那条已变成已确认（对照：动作真的生效了）" "1" \
  "$(curl -s "$BASE/alarms?pointId=$P1&status=CONFIRMED" -H "$AUTH" | data_of "['total']")"
check "项目 2 那条仍待确认（403 没有副作用）" "1" \
  "$(curl -s "$BASE/alarms?pointId=$P2&status=PENDING" -H "$AUTH" | data_of "['total']")"

section "⑧ 设备侧：无归属的设备只对 ADMIN 可见"
# 设备本身不挂项目，归属由 device_point 反推。刚建好、还没绑测点的设备**无归属**，
# 这时只对 ADMIN 可见——否则建完就「消失」，连挂测点的入口都没了。
DEV_B="DEV-SCP-$RUN_ID"
# lastReportTime 写在 2020 年：离线判据是 5 分钟窗口，这样不必真等。
# 用**不带时区的**字面量（与 07 套件同一构造）——实体字段是 LocalDateTime，
# 带 +08:00 的 ISO 串要靠 Jackson 的宽松解析，能不能进得去取决于全局配置，不值得赌。
DEV_ID=$(curl -s -X POST "$BASE/devices" -H "$AUTH" -H "$JSON" \
  -d "{\"code\":\"$DEV_B\",\"name\":\"隔离验收临时设备\",\"type\":\"MILLIMETER_WAVE_RADAR\",\"lastReportTime\":\"2020-01-01T00:00:00\"}" \
  | data_of "['id']")
check "临时设备已建" "true" "$(is_id "$DEV_ID")"
info "新建 $DEV_B -> deviceId=$DEV_ID（lastReportTime=2020-01-01，必判离线）"

check "admin 的设备列表里有它" "true" "$(curl -s "$BASE/devices" -H "$AUTH" | has_id "$DEV_ID")"
check "李敏的设备列表里没有它（无归属）" "false" \
  "$(curl -s "$BASE/devices" -H "$OP_AUTH" | has_id "$DEV_ID")"
check "李敏直达它的状态 -> 403" "403" "$(http_code "$BASE/devices/$DEV_ID/status" -H "$OP_AUTH")"
check "李敏读它的绑定列表 -> 403" "403" "$(http_code "$BASE/devices/$DEV_ID/points" -H "$OP_AUTH")"
check "李敏直达它的档案 -> 403" "403" "$(http_code "$BASE/devices/$DEV_ID" -H "$OP_AUTH")"

# 等离线扫描开出设备告警（间隔：--fresh 下 2s，其它 10s；轮询上限 40s，同 07/09 的约定）
n=0
until [ "$(curl -s "$BASE/alarms?deviceId=$DEV_ID" -H "$AUTH" | data_of "['total']")" != "0" ] || [ "$n" -ge 40 ]; do
  sleep 1; n=$((n + 1))
done
check "设备离线告警已产生" "1" \
  "$(curl -s "$BASE/alarms?deviceId=$DEV_ID" -H "$AUTH" | data_of "['total']")"
# 设备告警只写 device_id、point_id 为 NULL —— 这正是 B-14 漏掉的那一侧
check "李敏看不到这台无归属设备的警情" "0" \
  "$(curl -s "$BASE/alarms?deviceId=$DEV_ID" -H "$OP_AUTH" | data_of "['total']")"

# 绑到项目 2 的测点后，它对项目 2 的成员可见、对项目 1 的人仍不可见
# （设备的可见性是并集，加一个项目不会让它对别的项目也开放）
check "把它绑到项目 2 的临时测点" "200" \
  "$(http_code -X POST "$BASE/devices/$DEV_ID/points/$P2" -H "$AUTH")"
check "绑定后李敏仍看不到它的警情" "0" \
  "$(curl -s "$BASE/alarms?deviceId=$DEV_ID" -H "$OP_AUTH" | data_of "['total']")"
check "绑定后 admin 依然看得到" "1" \
  "$(curl -s "$BASE/alarms?deviceId=$DEV_ID" -H "$AUTH" | data_of "['total']")"

section "⑨ 影像：按编码直达也要挡住"
# /media/{mediaId}/content 拿的是顺序编码（M001），枚举成本极低——
# 列表端点滤得再干净，少了这一处也等于没隔离。
PNG="/tmp/scope-1x1-$RUN_ID.png"
python3 -c "
import base64
open('$PNG','wb').write(base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='))"
MID=$(curl -s -X POST "$BASE/media" -H "$AUTH" \
      -F "file=@$PNG;type=image/png" -F "pointId=$P2" -F "note=隔离验收" | data_of "['mediaId']")
# mediaId 是**不透明字符串编码**（MediaCode.of → "M1024"），不是数字 id——所以不用 is_id，
# 按它的格式判（M + 数字）。用「非空即真」的话，一个 401 的错误标记也算「挂上了」。
check "影像已挂到项目 2 的临时点" "true" "$(case "$MID" in M[0-9]*) echo true ;; *) echo false ;; esac)"
check "admin 能读该影像内容" "200" "$(http_code "$BASE/media/$MID/content" -H "$AUTH")"
check "李敏读该影像内容 -> 403" "403" "$(http_code "$BASE/media/$MID/content" -H "$OP_AUTH")"
check "李敏删该影像 -> 403" "403" "$(http_code -X DELETE "$BASE/media/$MID" -H "$OP_AUTH")"
rm -f "$PNG"

section "⑩ 维护记录随设备"
REC=$(curl -s -X POST "$BASE/maintenance-records" -H "$AUTH" -H "$JSON" \
      -d "{\"deviceId\":$DEV_ID,\"type\":\"INSPECT\",\"description\":\"隔离验收临时记录\"}" | data_of "['id']")
check "维护记录已建" "true" "$(is_id "$REC")"
check "admin 能读它" "200" "$(http_code "$BASE/maintenance-records/$REC" -H "$AUTH")"
check "李敏读它 -> 403" "403" "$(http_code "$BASE/maintenance-records/$REC" -H "$OP_AUTH")"
check "李敏按设备过滤维护记录 -> 403" "403" \
  "$(http_code "$BASE/maintenance-records?deviceId=$DEV_ID" -H "$OP_AUTH")"

section "⑪ 推送侧：查询挡住了不够，SSE 也得按订阅者挡（日志 §120 的根治）"
# 这一节为什么必须存在：⑨ 的「按编码直达」防的是**枚举**，而 SSE 是**推送**——
# 服务端不筛的话，「界面上根本看不到这个测点，告警横幅却把它弹了出来」，
# 而且别的项目的 pointCode / deviceCode 会真的到达不该看到的浏览器。
#
# 判据只认「警情事件」块（event: alarm 的下一条 data 行）里带业务码：
# **不能只 grep 业务码**——measurement 事件的载荷里也有 pointCode，
# 那样就分不清被滤掉的是哪一类事件，一个「只滤了警情、没滤测值」的实现照样能绿。
#
# 六条订阅（§⑧ 之后的**全新**流，所以里面出现的码只可能来自本节）：
#   admin / 李敏 / outsider 各一条，正反两侧都要。
# 负向断言靠「先等管理员收到、再宽限一段」来保证非空洞：
# 管理员收到了 = 事件确实发出去了，此时李敏仍没有才是「被滤掉」而不是「根本没发生」。
SSE_AD=$(mktemp); SSE_OP=$(mktemp); SSE_OUT=$(mktemp)
SSE_PIDS=""
sse_cleanup() {
  for p in $SSE_PIDS; do kill "$p" 2>/dev/null; done
  rm -f "$SSE_AD" "$SSE_OP" "$SSE_OUT"
}
trap sse_cleanup EXIT

curl -sN "$BASE/stream?token=$TOKEN"     > "$SSE_AD"  2>&1 & SSE_PIDS="$SSE_PIDS $!"
curl -sN "$BASE/stream?token=$OP_TOKEN"  > "$SSE_OP"  2>&1 & SSE_PIDS="$SSE_PIDS $!"
curl -sN "$BASE/stream?token=$OUT_TOKEN" > "$SSE_OUT" 2>&1 & SSE_PIDS="$SSE_PIDS $!"
sleep 1
check "三条订阅都建立了（admin / 李敏 / outsider）" "3" \
  "$(cat "$SSE_AD" "$SSE_OP" "$SSE_OUT" | grep -c 'event: *connected')"
# 订阅建立必须先于下面的事件，否则「没收到」只是「没在听」

# 在警情事件块里找某个字段值（-A1 把 event 行与它下面的 data 行一起取出来）
wait_alarm() {   # wait_alarm <文件> <JSON 键> <值> [秒]
  local f="$1" k="$2" v="$3" secs="${4:-15}" n=0
  while [ "$n" -lt $((secs * 4)) ]; do
    grep -A1 -- 'event: *alarm' "$f" 2>/dev/null | grep -q -- "\"$k\":\"$v\"" && return 0
    sleep 0.25; n=$((n + 1))
  done
  return 1
}
has_alarm() { grep -A1 -- 'event: *alarm' "$1" 2>/dev/null | grep -q -- "\"$2\":\"$3\""; }

# --- 正向对照先跑：李敏必须收得到**自己项目**的警情事件 ---
# 没有这一条，下面所有「没收到」都可以由一个 401 的订阅解释。
ingest_id "scp-push1-$RUN_ID" "$CODE1" "$T" '"defo_mm":5.5' >/dev/null
if wait_alarm "$SSE_OP" pointCode "$CODE1"; then
  pass "李敏收到了自己项目（项目 1）的警情事件——订阅是活的"
else
  fail "李敏没收到自己项目的警情事件（订阅坏了，下面的负向断言全部无意义）" \
       "$(tail -c 200 "$SSE_OP")"
fi
check "管理员也收到了项目 1 那条" "true" "$(has_alarm "$SSE_AD" pointCode "$CODE1" && echo true || echo false)"

# --- 反向：项目 2 的同一种事件，李敏与 outsider 都不该收到 ---
ingest_id "scp-push2-$RUN_ID" "$CODE2" "$T" '"defo_mm":5.5' >/dev/null
if wait_alarm "$SSE_AD" pointCode "$CODE2"; then
  pass "管理员收到了项目 2 的警情事件（说明事件确实发出去了）"
else
  fail "管理员没收到项目 2 的警情事件" "$(tail -c 200 "$SSE_AD")"
fi
sleep 2   # 宽限：给「本该被滤掉的那条」足够时间到达（若服务端没滤的话）
check "李敏的流里没有项目 2 的警情事件" "false" "$(has_alarm "$SSE_OP" pointCode "$CODE2" && echo true || echo false)"
check "outsider 的流里没有项目 2 的警情事件" "false" \
  "$(has_alarm "$SSE_OUT" pointCode "$CODE2" && echo true || echo false)"
check "outsider 的流里也没有项目 1 的（无项目 = 什么都收不到）" "false" \
  "$(has_alarm "$SSE_OUT" pointCode "$CODE1" && echo true || echo false)"
# 整个流里连**测值事件**也不该带出来——它同样带 pointCode
check "李敏的流里根本不出现项目 2 的点号（含测值事件）" "0" \
  "$(grep -c -- "$CODE2" "$SSE_OP")"

# --- 设备侧：无归属设备的告警只发 ADMIN（projectIdsOfDevice 的空集语义） ---
DEV_P="DEV-SCPP-$RUN_ID"
DEV_PID=$(curl -s -X POST "$BASE/devices" -H "$AUTH" -H "$JSON" \
  -d "{\"code\":\"$DEV_P\",\"name\":\"隔离推送验收临时设备\",\"type\":\"MILLIMETER_WAVE_RADAR\",\"lastReportTime\":\"2020-01-01T00:00:00\"}" \
  | data_of "['id']")
check "推送用临时设备已建（未绑任何测点）" "true" "$(is_id "$DEV_PID")"
# 离线扫描开出告警后，事件带的是 deviceCode——这条走的是与测点不同的归属链
if wait_alarm "$SSE_AD" deviceCode "$DEV_P" 40; then
  pass "管理员收到了这台无归属设备的告警事件"
else
  fail "管理员没收到无归属设备的告警事件" "$(tail -c 200 "$SSE_AD")"
fi
sleep 2
check "李敏的流里没有这台无归属设备的事件" "false" \
  "$(has_alarm "$SSE_OP" deviceCode "$DEV_P" && echo true || echo false)"
check "李敏的流里根本不出现这台设备的编码" "0" "$(grep -c -- "$DEV_P" "$SSE_OP")"

# 设备也过一遍 HTTP 侧：与推送**同一份判据**（两处若各写一套，就会出现一边挡一边漏）
check "同一台设备：李敏读它的状态 -> 403（与推送同判据）" "403" \
  "$(http_code "$BASE/devices/$DEV_PID/status" -H "$OP_AUTH")"
check "同一台设备：管理员读它的状态 -> 200" "200" \
  "$(http_code "$BASE/devices/$DEV_PID/status" -H "$AUTH")"

sse_cleanup
SSE_PIDS=""
recycle_device "$DEV_PID" "隔离推送验收临时设备"

section "⑫ 回收：套件自己不留垃圾"
# 顺序有讲究：**先删影像、再解绑、再结警情删设备/测点**。
# 影像行挂在测点上，测点一软删它就成孤儿（同 lib.sh 里 recycle_point 那段说的问题）；
# device_point 是硬删除、没有级联，不先解绑就会留下指向已删测点的悬空绑定行。
curl -s -o /dev/null -X DELETE "$BASE/media/$MID" -H "$AUTH"
curl -s -o /dev/null -X DELETE "$BASE/devices/$DEV_ID/points/$P2" -H "$AUTH"
recycle_device "$DEV_ID" "隔离验收临时设备"
recycle_point "$P2" "隔离验收临时点B（项目 2）"
recycle_point "$P1" "隔离验收临时点A（项目 1）"

check "回收后 admin 的测点数回到基线" "$ALL_P0" "$(curl -s "$BASE/points" -H "$AUTH" | count_of)"
check "回收后李敏的测点数回到基线" "$OP_P0" "$(curl -s "$BASE/points" -H "$OP_AUTH" | count_of)"
check "回收后 admin 的项目数回到基线" "$ALL_PRJ0" "$(curl -s "$BASE/projects" -H "$AUTH" | count_of)"
check "回收后李敏的设备数回到基线" "$OP_D0" "$(curl -s "$BASE/devices" -H "$OP_AUTH" | count_of)"

summary
