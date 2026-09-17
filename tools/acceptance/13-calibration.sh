#!/bin/bash
# 验收 13：标定失效与重新标定（清单第 09 条）——**位姿一变，旧标定不再自称有效**。
#
#   为什么单独一套件：本仓的习惯是一条不变式一个套件（11-concurrency、12-ingest-concurrency），
#   塞进 01 会让它翻三倍；而 01 验的是档案 CRUD 的权限与形状，与标定的生命周期无关。
#
#   本套件钉住的是一条**闭环**：
#     绑定 -> 标定 -> (设备被挪动/转向) -> 旧标定自动转 INVALID 并留痕 -> 重新标定 -> 回到 ACTIVE
#   外加这条闭环的三个反面：
#     - 不动几何的写入（GET 回来的 body 原样 PUT 回去）**不能**误伤标定（§②）；
#     - 接入路径**不**因为标定失效而拒收、也不回写状态（§⑧，那个决定见 CalibrationService 的
#       「拒收不改档案」注释与 IngestService#contractError）；
#     - 权限边界：能标定的人就能停用（MAINTAINER），但改档案仍然只有 ADMIN（§⑥/§⑦）；
#     - **测点被移动**同样让旧标定失效（§⑩）。与设备位姿是同一条不变式，但走的是另一条
#       路径（invalidateActiveOfPoint：一个测点可能被多台雷达同时观测），而 §⑦ 那张
#       「覆盖 update 时漏注解」的捕获网只罩住 /devices/{id} —— 所以 §⑩ 里另立了一张
#       MAINTAINER PUT /points/{id} -> 403 的网。
#
#   **这套件是 API 级的，界面上点不出这个场景**：前端没有设备/测点档案的编辑入口
#   （补齐表单是清单第 14 条），所以「设备被移动/转向」只能在这里用 curl 造。
#   交付说明里必须写明这一点，否则「界面上没有」容易被当成「没做」。
#
#   夹具坑一（不写下来要花一小时）：RadarCoveragePolicy.validateCalibration 会拒绝斜距超
#   detectionRangeM、方位/俯仰出 FOV 的标定。临时设备若不带上 detectionRangeM /
#   halfAngleDegrees / verticalHalfAngleDegrees / headingDegrees（外加 pitchDegrees），
#   这四项全是 null -> **每一次标定都 400「目标斜距超出雷达量程」**。
#
#   夹具坑二：§⑦ 要用 MAINTAINER 令牌调端点，而设备的可见性是由 device_point 反查
#   测点 -> 对象 -> 场景 -> 项目得出的（DataScopeService.projectIdsOfDevice），
#   没绑测点的新设备只对 ADMIN 可见。所以临时测点必须建在**项目 1**（objectId=1，
#   四个演示账号都在这个项目里，见 ProjectMemberInitializer），否则 MAINTAINER 会因为
#   「看不到这台设备」而拿到 403 —— 那正好是 §⑦ 期望的码，断言会因为完全错误的原因变绿。
#
#   夹具坑三：§③ 把航向从 0 改到 20 度，之后每次（重新）标定的方位角都必须落在**新**航向的
#   水平视场内。所以方位角全程取 10 度：对 0 度航向是 |10-0|=10，对 20 度航向是 |10-20|=10，
#   都在 30 度半角内。手动验证清单里若照抄 300 度改航向，重新标定那一条会 400——预期如此。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

TOKEN=$(login)
AUTH="Authorization: Bearer $TOKEN"
MTOKEN=$(login_as maintainer)
MAUTH="Authorization: Bearer $MTOKEN"
# 令牌拿不到必须当场退出（退出码 2 = 环境问题），不能继续跑：空令牌会让每条 MAINTAINER
# 请求都变成 401/403，而 §⑦ 期望的恰恰是 403 —— 一个拼错的变量名会伪装成「权限校验正确」。
# 10-scope.sh 的头注释记的正是这个跟头（AUTH=$(login) 少了 Bearer 那一段）。
[ -n "$MTOKEN" ] || {
  printf '%s未能登录 maintainer（账号/口令不对？）%s\n' "$C_RED" "$C_OFF" >&2
  exit 2
}

DEV="dev-cal-$RUN_ID"
NP="P-CAL-$RUN_ID"

# bind_field <字段名>：读回临时设备上那条绑定的字段。
#   **读回走既有端点**（GET /devices/{id}/points），不新增读接口；null 打印 None，
#   这样断言里可以直接比较 `None` 与具体值。
bind_field() {
  curl -s "$BASE/devices/$DID/points" -H "$AUTH" | python3 -c "
import sys, json
rs = json.load(sys.stdin)['data']
row = next((r for r in rs if r['pointId'] == $PT), None)
if row is None:
    print('<未找到绑定>')
else:
    v = row.get('$1')
    print('None' if v is None else v)
"
}

# bind_present <字段名>：该字段非 null 时打印 True（用于「留痕有没有留下」这类断言）
bind_present() {
  curl -s "$BASE/devices/$DID/points" -H "$AUTH" | python3 -c "
import sys, json
rs = json.load(sys.stdin)['data']
row = next((r for r in rs if r['pointId'] == $PT), None)
v = None if row is None else row.get('$1')
print('True' if v is not None else 'False')
"
}

# calibrate <JSON body> -> 标定响应体（含 data）
calibrate() {
  curl -s -X PUT "$BASE/devices/$DID/points/$PT/calibration" -H "$AUTH" -H "$JSON" -d "$1"
}

# 标定请求体：字段串单独拎出来，是因为 §⑤ 需要「同一份标定 + 一个 validTo」的变体。
# （写成 `${CAL_BODY%}}` 去砍尾括号是不行的：bash 在第一个 `}` 就闭合了参数展开，
#   结果是原样照抄、多出一个花括号 —— 直接拼字段串最不容易出这种哑巴错。）
CAL_FIELDS="\"targetCode\":\"TGT-CAL-$RUN_ID\",\"azimuthDegrees\":10.0,\"elevationDegrees\":5.0,\"slantRangeM\":20.0,\"reflectorHeightM\":1.0,\"minimumClearanceM\":1.0,\"lineOfSight\":true,\"note\":\"验收临时标定\""
CAL_BODY="{$CAL_FIELDS}"

section "⓪ 夹具：几何齐全的临时设备 + 项目 1 下的临时测点 + 绑定"
# 几何五项一个都不能少（夹具坑一）。
DID=$(curl -s -X POST "$BASE/devices" -H "$AUTH" -H "$JSON" \
      -d "{\"code\":\"$DEV\",\"name\":\"标定验收临时雷达\",\"type\":\"MILLIMETER_WAVE_RADAR\",
           \"longitude\":113.0500000,\"latitude\":23.7200000,\"altitude\":60.000,
           \"antennaHeightM\":10.000,\"headingDegrees\":0,\"pitchDegrees\":0,
           \"detectionRangeM\":265.000,\"halfAngleDegrees\":30.0000,\"verticalHalfAngleDegrees\":15.0000,
           \"battery\":90.0,\"status\":\"ONLINE\"}" | data_of "['id']")
# objectId=1 在项目 1（PRJ-QY-HK）下：MAINTAINER 才看得到这台设备（夹具坑二）
PT=$(curl -s -X POST "$BASE/points" -H "$AUTH" -H "$JSON" \
     -d "{\"objectId\":1,\"code\":\"$NP\",\"name\":\"标定验收临时测点\",\"type\":\"POINT_DEFORMATION\",\"enabled\":true}" \
     | data_of "['id']")
info "deviceId=$DID（$DEV）/ pointId=$PT（$NP）"
check "设备与测点绑定成功" "200" "$(http_code -X POST "$BASE/devices/$DID/points/$PT" -H "$AUTH")"
# 全仓此前对 calibration_status **零断言**：这条是「绑定不等于标定」的基线。
check "新绑定是 PENDING（从未标定，不是已失效）" "PENDING" "$(bind_field calibrationStatus)"

section "① 标定 -> ACTIVE"
check "带 lineOfSight 的标定成功" "200" \
  "$(http_code -X PUT "$BASE/devices/$DID/points/$PT/calibration" -H "$AUTH" -H "$JSON" -d "$CAL_BODY")"
check "标定后状态为 ACTIVE" "ACTIVE" "$(bind_field calibrationStatus)"
check "从未失效过（invalidated_at 为空）" "None" "$(bind_field invalidatedAt)"

section "② 原样回传档案 -> 不误判为位姿变更"
# **本节是 compareTo 与 equals 之别的端到端回归。** device.heading_degrees 是 NUMERIC(9,4)，
# longitude 是 NUMERIC(12,7)：从库里读回来是 scale 4 / 7，而 Jackson 反序列化 "0" 是 scale 0。
# 判定若写成 BigDecimal.equals（连 scale 一起比），每一次「读出来原样存回去」都会被算成
# 位姿变了 —— 那不是「保守」而是**每次保存都误伤**：现场点一次保存，所有标定全部退休。
# 单测里有同一件事的纯函数版本（RadarCoveragePolicyTest#poseChangedIgnoresScaleOnlyDifference）。
BODY=$(curl -s "$BASE/devices/$DID" -H "$AUTH" | python3 -c "
import sys, json
print(json.dumps(json.load(sys.stdin)['data']))")
check "原样 PUT 回去成功" "200" \
  "$(http_code -X PUT "$BASE/devices/$DID" -H "$AUTH" -H "$JSON" -d "$BODY")"
check "原样回传后标定仍是 ACTIVE（scale 差异不算位姿变更）" "ACTIVE" "$(bind_field calibrationStatus)"

section "③ 改航向 -> 旧标定自动失效并留痕"
# 航向 0 -> 20 度。旧标定的方位角/斜距是按旧朝向量出来的，继续挂 ACTIVE 就是在说一句
# 已经不成立的话（清单第 09 条）。
check "改几何的 PUT 返回 200" "200" \
  "$(http_code -X PUT "$BASE/devices/$DID" -H "$AUTH" -H "$JSON" -d '{"headingDegrees":20.0}')"
check "标定转为 INVALID" "INVALID" "$(bind_field calibrationStatus)"
check "留下失效时刻 invalidated_at" "True" "$(bind_present invalidatedAt)"
check "失效原因是 DEVICE_POSE_CHANGED" "DEVICE_POSE_CHANGED" "$(bind_field invalidatedReason)"
# invalidated_by 记的是改档案的那个 ADMIN（几何变更由系统判定，操作者仍是人）
check "失效痕迹记下操作者 admin" "admin" "$(bind_field invalidatedBy)"

section "④ 重新标定 -> 回到 ACTIVE 且痕迹清空"
# **null 跳过 trap 的端到端回归**：MyBatis-Plus 的 updateById 跳过 null 字段，所以
# 「把 invalidated_* 清空」只能靠 DevicePointMapper#applyCalibration 那条显式 SQL。
# 用 updateById 的话这一节会红两次：状态虽然是 ACTIVE，三个痕迹列却还留着旧值，
# 界面上会同时显示「有效」与「已失效」。
RESP=$(calibrate "$CAL_BODY")
check "重新标定成功" "ACTIVE" "$(printf '%s' "$RESP" | data_of "['calibrationStatus']")"
# 响应体本身必须干净：控制层返回的是**回读**的那一行，不是失效之前的快照。
# 若直接返回内存里的 binding，这里会实得一个旧的 invalidatedAt（而库里其实是 NULL）。
check "响应里的 invalidatedAt 已是空" "None" "$(printf '%s' "$RESP" | data_of "['invalidatedAt']")"
check "库里也确认清空" "None" "$(bind_field invalidatedAt)"
check "库里确认失效原因清空" "None" "$(bind_field invalidatedReason)"
check "库里确认失效操作者清空" "None" "$(bind_field invalidatedBy)"

section "⑤ valid_to 曾非空 -> 不带 validTo 重标定 -> 回到「永久有效」"
# 另一个 null 跳过 trap：`valid_to` 一旦写过就再也清不掉，于是「失效 -> 从界面重新标定」
# 会生出一条**一出生就已过期**的绑定（对话框里根本没有有效期输入框，它只会沿用旧值）。
# 先造一个非空 validTo，再不带它重标定一次，钉住 applyCalibration 的显式 SQL。
# 复用 lib.sh 的 ts()：+8 时区、带偏移的 ISO8601，而 JacksonTimeConfig 的宽容反序列化器
# 正是为这种写法准备的（Times.parse 统一口径）。30 天 = 43200 分钟。
D30=$(ts 43200)
RESP=$(calibrate "{$CAL_FIELDS,\"validTo\":\"$D30\"}")
check "带 validTo 的标定成功" "ACTIVE" "$(printf '%s' "$RESP" | data_of "['calibrationStatus']")"
check "validTo 已写入（本节的前提）" "True" "$(bind_present validTo)"
# 不带 validTo：表意是「永久有效」，不是「沿用上一次」
check "不带 validTo 重标定成功" "200" \
  "$(http_code -X PUT "$BASE/devices/$DID/points/$PT/calibration" -H "$AUTH" -H "$JSON" -d "$CAL_BODY")"
check "validTo 回到 null（没有沿用上一次的有效期）" "None" "$(bind_field validTo)"
check "状态仍是 ACTIVE" "ACTIVE" "$(bind_field calibrationStatus)"

section "⑥ 人工停用：ADMIN 生效且幂等；MAINTAINER 也有权停用"
check "ADMIN 停用返回 200" "200" \
  "$(http_code -X DELETE "$BASE/devices/$DID/points/$PT/calibration" -H "$AUTH")"
check "停用后状态为 INVALID" "INVALID" "$(bind_field calibrationStatus)"
check "不带原因时记为 MANUAL" "MANUAL" "$(bind_field invalidatedReason)"
FIRST_AT=$(bind_field invalidatedAt)
info "首次停用时刻 invalidatedAt=$FIRST_AT"
# 幂等：第二次 200 但**不改动**第一次留下的痕迹（否则「谁在什么时候停用的」会变成
# 最后一次点按钮的人和时间）。
check "重复停用仍返回 200" "200" \
  "$(http_code -X DELETE "$BASE/devices/$DID/points/$PT/calibration" -H "$AUTH")"
check "重复停用不改写失效时刻" "$FIRST_AT" "$(bind_field invalidatedAt)"
# 与 bind/calibrate/unbind 的角色口径一致：能标定的就能停用（刻意的不对称：
# 自动失效挂在 PUT /devices/{id} 上，那个是 ADMIN-only）。
calibrate "$CAL_BODY" >/dev/null
check "（前置）重新标定回到 ACTIVE" "ACTIVE" "$(bind_field calibrationStatus)"
check "MAINTAINER 停用返回 200" "200" \
  "$(http_code -X DELETE "$BASE/devices/$DID/points/$PT/calibration" -H "$MAUTH")"
check "MAINTAINER 的停用真的生效了" "INVALID" "$(bind_field calibrationStatus)"
check "操作者记为 maintainer" "maintainer" "$(bind_field invalidatedBy)"

section "⑦ MAINTAINER 改档案 -> 403（覆盖 update 时漏 @PreAuthorize 的捕获网）"
# 覆盖 BaseCrudController.update 时三个注解必须逐字重声明：Java 的方法注解不继承，
# 而 Spring Security 取的是**最具体**那个方法的注解。漏掉 @PreAuthorize 的话，
# 这个 ADMIN-only 端点会静默变成「任何已登录用户可写」——而其它 12 个套件全部用
# admin 令牌跑，一条都不会红。这里就是那张网。
check "MAINTAINER PUT 设备档案被拒" "403" \
  "$(http_code -X PUT "$BASE/devices/$DID" -H "$MAUTH" -H "$JSON" -d '{"headingDegrees":30.0}')"
check "被拒的请求没有留下任何副作用（航向仍是 20）" "True" \
  "$(curl -s "$BASE/devices/$DID" -H "$AUTH" | python3 -c "
import sys, json
d = json.load(sys.stdin)['data']
print(d['headingDegrees'] is not None and abs(float(d['headingDegrees']) - 20.0) < 1e-9)")"

section "⑧ 失效没有接进接入路径：INVALID 之后上报照收、状态不被回写"
# 这是**刻意的决定**（见 IngestService#contractError 的注释）：接入批次是无状态、幂等、
# 被契约要求可重试的，而失效是一次档案写操作。把档案变更藏在一个拒收理由后面，
# 同一个请求第二次就会返回不同答案。所以自动失效只挂在档案维护路径上。
# 非严格契约（全部验收套件与 dev 的默认）下，DEVICE_POINT_NOT_CALIBRATED 这类判定
# 整段不跑 —— 这一节同时钉住那个短路没有被顺手拆掉。
AFTER=$(ingest_id "cal-$RUN_ID-post" "$NP" "$(ts 0)" '"defo_mm":1.0' "$DEV")
check "标定已失效，报文照常收下" "1" "$(printf '%s' "$AFTER" | data_of "['accepted']")"
check "没有被拒收" "0" "$(printf '%s' "$AFTER" | data_of "['rejected']")"
check "上报没有回写标定状态（仍是 INVALID）" "INVALID" "$(bind_field calibrationStatus)"

section "⑨ 后端日志里的失效证据"
# 本仓成文的证据形式（先例 12-ingest-concurrency.sh）：从状态串只能**反推**「大概失效了」，
# 而一条新鲜的 INVALID 也可能来自人工停用或别的原因。日志行带 deviceId + 计数，
# 才证明「设备几何变更」这条路真的被走到了、且那次正好失效了 1 条。
if [ -n "${BACKEND_LOG:-}" ] && [ -f "$BACKEND_LOG" ]; then
  check_grep "日志留下失效痕迹：本设备 invalidated=1" \
    "设备几何变更使标定失效 deviceId=$DID code=$DEV invalidated=1" "$BACKEND_LOG"
  check_grep "日志里的原因码是 DEVICE_POSE_CHANGED" \
    "deviceId=$DID code=$DEV invalidated=1 reason=DEVICE_POSE_CHANGED" "$BACKEND_LOG"
else
  info "未提供 BACKEND_LOG（跑在别人已启动的后端上），跳过日志证据断言；"
  info "要看这条证据请用：bash tools/acceptance/run-all.sh --fresh"
fi

section "⑩ 测点移动 -> 旧标定失效（清单第 09 条的测点路径）"
# 与 §③ 对称：③ 钉设备位姿，这一条钉测点位移。这条路径此前**零验收覆盖**
# （13 个套件里没有一处 PUT /points/{id}），而 §⑦ 那张「覆盖 update 时漏注解」的捕获网
# 只罩住 /devices/{id}：MonitorPointController.update 漏一个 @PreAuthorize，
# 这个 ADMIN-only 端点就静默变成「任何已登录用户可写」——其余 12 个套件全用 admin 令牌跑，
# 一条都不会红。
#
# 前置：§⑥ 结束时这条绑定是 INVALID（MAINTAINER 人工停用），必须先回到 ACTIVE。
# 少了这一步，「标定转为 INVALID」会因为**本来就是 INVALID**而假绿，日志那行还会写
# invalidated=0 ——而「那次正好失效了 1 条」正是下面的日志断言要抓的东西。
calibrate "$CAL_BODY" >/dev/null
check "（前置）重新标定回到 ACTIVE" "ACTIVE" "$(bind_field calibrationStatus)"

# 移动测点：只提一个 altitude。夹具（§⓪）建点时没带任何几何字段，该点三列全为 null，
# altitude 一填就是「从无到有」-> differs 的第③条判为改变。
# 用 altitude 而不是经纬度：三列在 pointMoved 里权重相同（RadarCoveragePolicy:143-148），
# 但 altitude 是 NUMERIC(10,3)，比经纬度的 NUMERIC(12,7) 位数少，字面量与库里回读的
# scale 对齐后最不容易留噪声。
check "移动测点的 PUT 返回 200" "200" \
  "$(http_code -X PUT "$BASE/points/$PT" -H "$AUTH" -H "$JSON" -d '{"altitude":55.000}')"
check "标定转为 INVALID" "INVALID" "$(bind_field calibrationStatus)"
check "留下失效时刻 invalidated_at" "True" "$(bind_present invalidatedAt)"
check "失效原因是 POINT_MOVED" "POINT_MOVED" "$(bind_field invalidatedReason)"
# 与 §③ 同一口径：几何变更由系统判定，操作者记的是改档案的那个人（AuditAspect 同源）。
check "失效痕迹记下操作者 admin" "admin" "$(bind_field invalidatedBy)"

# 重新标定 -> 回到 ACTIVE 且痕迹清空。与 §④ 是同一条 applyCalibration 显式 SQL，
# 这里再钉一次是因为失效来源换了：POINT_MOVED 走的是 invalidateActiveOfPoint。
RESP=$(calibrate "$CAL_BODY")
check "移动后重新标定成功" "ACTIVE" "$(printf '%s' "$RESP" | data_of "['calibrationStatus']")"
check "库里确认 invalidated_at 清空" "None" "$(bind_field invalidatedAt)"
check "库里确认失效原因清空" "None" "$(bind_field invalidatedReason)"

# 误报守卫（反过拟合）：不碰几何的写入不得失效。此刻该点的 altitude 已经是 55.000，
# 走的是「before 非 null、after 为 null」那条分支（differs 第②条），比几何全空时更有区分度。
# 名字用 ASCII：这个期望值要经过 python3 的 stdout，跟着跑机器的 locale 走。
check "只改名字的 PUT 返回 200" "200" \
  "$(http_code -X PUT "$BASE/points/$PT" -H "$AUTH" -H "$JSON" -d '{"name":"cal-renamed"}')"
check "不碰几何 -> 标定仍是 ACTIVE（没有误伤）" "ACTIVE" "$(bind_field calibrationStatus)"

# 注解接线捕获网一：与 §⑦ 同款，罩住 MonitorPointController.update 的 @PreAuthorize。
# 漏掉它这一条实得 200（而不是 403）。
check "MAINTAINER PUT 测点档案被拒" "403" \
  "$(http_code -X PUT "$BASE/points/$PT" -H "$MAUTH" -H "$JSON" -d '{"name":"cal-rejected-rename"}')"
check "被拒的请求没有留下副作用（名字仍是上一次改的）" "cal-renamed" \
  "$(curl -s "$BASE/points/$PT" -H "$AUTH" | data_of "['name']")"

# 注解接线捕获网二：同一个方法上第三个必须逐字重声明的注解是 @Valid。
# MonitorPoint.code 是全实体**唯一**带约束的字段（@Size(max=64)），漏掉 @Valid 就一点校验
# 都没有，超长点号会一路落到 VARCHAR(64) 上（H2/PG 都报错 -> 500，不是静默成功）。
# 65 个字符全是 ASCII：不撞 uk_monitor_point_code，也不经过 python3 的 stdout。
LONG_CODE=$(printf 'a%.0s' {1..65})
check "超长点号被 @Valid 拦下" "400" \
  "$(http_code -X PUT "$BASE/points/$PT" -H "$AUTH" -H "$JSON" -d "{\"code\":\"$LONG_CODE\"}")"

# 日志证据：与 §⑨ 同一理由——状态串只能**反推**「大概失效了」，而一条新鲜的 INVALID
# 也可能来自人工停用。日志行带 pointId + 计数 + 原因码，才证明这条路真的被走到了、
# 且那次正好失效 1 条。
if [ -n "${BACKEND_LOG:-}" ] && [ -f "$BACKEND_LOG" ]; then
  check_grep "日志留下测点失效痕迹：本测点 invalidated=1" \
    "测点位置变更使标定失效 pointId=$PT code=$NP invalidated=1 reason=POINT_MOVED" "$BACKEND_LOG"
else
  info "未提供 BACKEND_LOG（跑在别人已启动的后端上），跳过测点侧的日志证据断言；"
  info "要看这条证据请用：bash tools/acceptance/run-all.sh --fresh"
fi

section "⑪ 回收"
# 解绑必须**早于** recycle_point：unbind 走 requirePoint -> pointMapper.selectById，
# 而 monitor_point 是逻辑删除，点一软删那里就 404 了。
# device_point 没有软删列，这条 DELETE 是**硬删行**，正好把本次造出来的绑定彻底抹掉，
# 不给后续套件（尤其 10-scope 的 pointCount 差值）留残留。
curl -s -o /dev/null -X DELETE "$BASE/devices/$DID/points/$PT" -H "$AUTH"
recycle_point "$PT" "临时测点 $NP"
recycle_device "$DID" "临时设备 $DEV"

summary
