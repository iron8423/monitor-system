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

section "② 种子档案：5 项目 / 6 场景 / 27 测点"
# 2026-09-14：项目 2（西江水泥采空区，V8）加入后，这两个数各 +1 / +2。
# 2026-09-18：V20 加入三个演示场景（野外桥梁 / 山区铁路 / 郊外工厂，各 1 项目 +
#   1 场景 + 1 对象 + 6 测点），于是 2/3/9 → 5/6/27。admin 是全量视角，
#   「看得到」这件事没变，变的是种子的规模。
check "项目数" "5" "$(curl -s "$BASE/projects" -H "$AUTH" | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']))")"
check "测点数" "27" "$(curl -s "$BASE/points" -H "$AUTH" | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']))")"
check "测点 1 号点号" "P-HK01" "$(curl -s "$BASE/points/1" -H "$AUTH" | data_of "['code']")"
check "每点 2 测项（D1）" "2" "$(curl -s "$BASE/metrics" -H "$AUTH" | python3 -c "
import sys,json
ms=json.load(sys.stdin)['data']
print(len([m for m in ms if m['pointId']==1]))")"

section "③ 设备档案（在线判定归 A，契约 §5）"
# 2026-09-16：V11（双雷达精细山体）加入南侧雷达 radar-002 后，这个数 1 -> 2。
# 与 ② 同理：变的不是「看得到」，而是种子的规模。改前这条一直红（`--fresh` 是空库，
# 种子只有迁移写入，所以 H2 上必然得到 2），只是全仓没人跑过整套验收。
check "设备数" "5" "$(curl -s "$BASE/devices" -H "$AUTH" | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']))")"
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
recycle_point "$PID"

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

section "⑦ 档案时间带时区（契约 §0）"
# 档案 CRUD 直接把实体原样返回（BaseCrudController），实体里是裸 LocalDateTime。
# Jackson 默认会把 LocalDateTime 写成 "2026-08-27T15:05:00"——不带偏移，前端无从判断时区。
# 全仓由 JacksonTimeConfig 统一补 +08:00；这里抽查两类不同实体的 createdAt，证明是全局行为而非逐字段特判。
has_zone() {
  python3 -c "
import sys,json
v=str(json.load(sys.stdin)['data']$1)
print(v.endswith('+08:00'))"
}
check "测点 createdAt 带偏移" "True" "$(curl -s "$BASE/points/1" -H "$AUTH" | has_zone "['createdAt']")"
check "项目 updatedAt 带偏移" "True" "$(curl -s "$BASE/projects/1" -H "$AUTH" | has_zone "['updatedAt']")"
check "设备 updatedAt 带偏移" "True" "$(curl -s "$BASE/devices/1" -H "$AUTH" | has_zone "['updatedAt']")"
info "示例 points/1.createdAt = $(curl -s "$BASE/points/1" -H "$AUTH" | data_of "['createdAt']")"

section "⑧ 档案时间：读出来的时间要能写回去（契约 §0）"
# ⑦ 验的是**输出**口径；这里验**输入**口径，两者是一份契约的两端，必须成对。
#
# 曾经的缺陷：序列化被 JacksonTimeConfig 统一补成带偏移的 2026-09-14T11:34:45+08:00，
# 而反序列化仍走 Jackson 默认实现——**只认不带偏移**的写法。于是「GET 回来原样 PUT 回去」
# 直接 400，表现成「接口收下了但字段改不动」（实际是请求根本没进方法）。
# 影响面不是某一个字段：Device.lastReportTime 与 BaseEntity.createdAt/updatedAt
# （后者被全部 7 个 CRUD 控制器继承）都在内。
#
# 用临时设备而不是种子 radar-001：本组要改 status，不能把种子设备留在 FAULT 上。
RT_DEV="DEV-RT-$RUN_ID"
RT_ID=$(curl -s -X POST "$BASE/devices" -H "$AUTH" -H "$JSON" \
        -d "{\"code\":\"$RT_DEV\",\"name\":\"验收临时设备\",\"type\":\"MILLIMETER_WAVE_RADAR\"}" | data_of "['id']")
info "新建 $RT_DEV -> deviceId=$RT_ID"

# ① 原样回传：把 GET 到的整个 data 对象（含 id/deleted/createdAt/updatedAt/lastReportTime）当请求体
RT_BODY=$(curl -s "$BASE/devices/$RT_ID" -H "$AUTH" | python3 -c "
import sys,json;print(json.dumps(json.load(sys.stdin)['data'], ensure_ascii=False))")
check "GET 到的设备原样 PUT 回去 -> 200" "200" \
  "$(http_code -X PUT "$BASE/devices/$RT_ID" -H "$AUTH" -H "$JSON" -d "$RT_BODY")"

# ② 带偏移的时间：这正是 Times.iso 的输出写法，也是当初 400 的触发点
RT_TIME=$(python3 -c "
import datetime
print(datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).replace(microsecond=0).isoformat())")
info "回填带偏移的时间 $RT_TIME"
check "带 +08:00 偏移的时间被接受 -> 200" "200" \
  "$(http_code -X PUT "$BASE/devices/$RT_ID" -H "$AUTH" -H "$JSON" \
     -d "{\"code\":\"$RT_DEV\",\"name\":\"验收临时设备\",\"type\":\"MILLIMETER_WAVE_RADAR\",\"lastReportTime\":\"$RT_TIME\",\"status\":\"FAULT\"}")"
check "带偏移的时间按原值（同一时刻）落库" "$RT_TIME" \
  "$(curl -s "$BASE/devices/$RT_ID" -H "$AUTH" | data_of "['lastReportTime']")"
# 同一次请求里的 status 也要落下去：证明整个请求体是被解析了的，不是「恰好 200」
check "同一次 PUT 的 status 也落库" "FAULT" \
  "$(curl -s "$BASE/devices/$RT_ID/status" -H "$AUTH" | data_of "['status']")"

# ③ 反向：真正非法的写法仍要挡住（容忍范围是「三种已知写法」，不是「照单全收」）
check "非法时间写法仍 -> 400" "400" \
  "$(http_code -X PUT "$BASE/devices/$RT_ID" -H "$AUTH" -H "$JSON" \
     -d "{\"code\":\"$RT_DEV\",\"name\":\"验收临时设备\",\"type\":\"MILLIMETER_WAVE_RADAR\",\"lastReportTime\":\"昨天下午\"}")"

recycle_device "$RT_ID" "临时设备 $RT_DEV"

section "⑨ 项目列表顺序必须确定（3D 大屏的默认项目依赖它）"
#
# 为什么这条要进套件：`GET /projects` 原先没有 ORDER BY，返回顺序由执行计划决定。
# 2026-09-17 实测到一次——同一份数据返回的是 id=2（西江水泥采空区，**没配数字孪生场景**）
# 排在最前，而前端 `monitor.js` 拿 `projects[0]` 当默认项目，于是 3D 大屏默认打开一个
# 没场景的项目，屏幕上只剩「场景未配置 / 离线底色」——看起来就是「大屏打不开」。
# 与「取最新一行必须带 id 兜底」同类：顺序被当成结论用，就必须写死。
check "项目列表首位是 id=1（清远山地边坡）" "1" \
  "$(curl -s "$BASE/projects" -H "$AUTH" | python3 -c "import sys,json;print(json.load(sys.stdin)['data'][0]['id'])")"
check "分页第一页与列表同序（不排序的分页会翻出重复行）" "1" \
  "$(curl -s "$BASE/projects/page?pageNum=1&pageSize=1" -H "$AUTH" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['records'][0]['id'])")"

section "⑩ 系统运维接口（新增，2026-09-18）：只读、仅 ADMIN、不回显密钥"
#
# 运维页（/ops）读的就是这四个端点。它和审计日志同一档权限，但泄密面更大
# （连接串、数据量、运行时开关），所以三条边界都要有断言：
#   ① 管理员能读；② 业务角色读不到（403，不是 200 也不是 404）；③ 响应里不出现密钥明文。
check "admin 可读 /ops/status" "200" "$(http_code "$BASE/ops/status" -H "$AUTH")"
check "admin 可读 /ops/migrations" "200" "$(http_code "$BASE/ops/migrations" -H "$AUTH")"
check "非管理员读 /ops/status -> 403" "403" \
  "$(http_code "$BASE/ops/status" -H "Authorization: Bearer $(login_as operator)")"
check "无令牌读 /ops/status -> 401" "401" "$(http_code "$BASE/ops/status")"
# 只回显"是否仍是默认值"，不回显内容：用开发默认密钥的字面量做探针
check "运维配置不回显接入密钥明文" "0" \
  "$(curl -s "$BASE/ops/config" -H "$AUTH" | grep -c "${INGEST_KEY:-dev-ingest-key}")"
# P2-9：行数统计里 measurement 走近似值，必须**标出来**（响应里列出近似表并附口径说明），
# 且整体缓存 60 秒——不然运维页每次刷新都把十几条查询（含一次大表计数）重打一遍。
check "admin 可读 /ops/stats" "200" "$(http_code "$BASE/ops/stats" -H "$AUTH")"
check "stats 列出了走近似计数的表" "True" \
  "$(curl -s "$BASE/ops/stats" -H "$AUTH" | python3 -c "import sys,json;print('measurement' in (json.load(sys.stdin)['data'].get('approximateTables') or []))")"
curl -s -o /dev/null "$BASE/ops/stats" -H "$AUTH"
check "第二次读 /ops/stats 命中缓存（cached=true）" "True" \
  "$(curl -s "$BASE/ops/stats" -H "$AUTH" | python3 -c "import sys,json;print(json.load(sys.stdin)['data'].get('cached'))")"

section "⑪ 列表端点的上限与「截断要说出来」（P1-3）"
#
# 列表端点此前没有上限：生产基线 1000 测点尚可，再长一个数量级就是每次刷新几十 MB。
# 现在的口径是「默认 2000、可用 ?limit= 调、上限 10000」，并且**被截断时用响应头说出来**
# ——少几行与"本来就只有几行"在响应体里长得一模一样，静默截断比报错更难查。
# 三条断言各自钉一件事：limit 生效、未截断时标记 false、默认口径下基线规模不被截。
HD=$(mktemp); HD2=$(mktemp); HD3=$(mktemp)
curl -s -D "$HD" -o /dev/null "$BASE/points?limit=2" -H "$AUTH"
curl -s -D "$HD2" -o /dev/null "$BASE/points" -H "$AUTH"
curl -s -D "$HD3" -o /dev/null "$BASE/points?limit=999999" -H "$AUTH"
check "limit=2 -> 响应里恰好 2 条" "2" \
  "$(curl -s "$BASE/points?limit=2" -H "$AUTH" | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']))")"
check_grep "limit=2 时回 X-Result-Limit: 2" "^X-Result-Limit: 2" "$HD"
check_grep "limit=2 时回 X-Result-Truncated: true（截断被明说）" "^X-Result-Truncated: true" "$HD"
check_grep "默认（不传 limit）不截断时回 false" "^X-Result-Truncated: false" "$HD2"
check "limit 超上限被夹到 10000（并把生效值回给调用方）" "10000" \
  "$(sed -n 's/^X-Result-Limit: *//p' "$HD3" | tr -d '\r')"
check "limit=1 -> 1 条（下界也夹住）" "1" \
  "$(curl -s "$BASE/points?limit=1" -H "$AUTH" | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']))")"
rm -f "$HD" "$HD2" "$HD3"

summary
