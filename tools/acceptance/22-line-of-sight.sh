#!/bin/bash
# 验收 22：地形通视试算与"声称通视但模型判定遮挡"的拒收（复查清单 P1-10）。
#
#   改造前：`device_point.line_of_sight` 是**离线生成器写进迁移的一个布尔**，
#   平台自己没有地形、也没有任何复核能力。新绑的目标、挪动过的雷达，只能靠人填。
#   现在后端带上同一份高程场（`backend/src/main/resources/terrain/*.bin`，
#   由 tools/terrain_asset/export_heightfield.py 从同一份 DEM 素材重建），
#   于是有了两件事：标定前的**试算**（只算不写）与提交时的**地形复核**。
#
#   本套件钉七件事：
#     ① 试算复现迁移里那条视线（radar-001 → P-HK01：通视、净空 1.438m）——
#        这是"后端与离线生成器同一口径"的现场证据（单测里也有一条，那条更全）；
#     ② 试算**不写库**：算完绑定仍是 PENDING / lineOfSight=false；
#     ③ 模型算出来的几何（方位/俯仰/斜距）与迁移里的值一致；
#     ④ 天线高可以覆盖试算：抬到 20m 后净空从 1.438 变 2.185（**不是 +10m**，
#        遮挡点离雷达很远，平行抬高只赚到一点点——这条正是试算存在的意义）；
#     ⑤ 真被遮挡的目标：试算判 BLOCKED，提交"通视"标定被 400 拒收且给出遮挡原因，
#        并且**拒收不改档案**（绑定仍然是 PENDING / lineOfSight=false）；
#     ⑥ 判据边界：模型范围外不给结论、也不拦标定（30m DEM 在场景外没有发言权）；
#     ⑦ 可见性口径与读测点一致（值班员对范围外测点的试算是 403，不是 200）。
#
#   数值断言一律带容差：迁移里的角度/斜距/净空是生成器用**精确本地坐标**算的，
#   档案里存的是四舍五入到 7 位小数的经纬度，后端从经纬度反算本地坐标必然带进
#   厘米级还原误差（实测 方位 +0.001°、斜距 +0.009m、净空 +0.001m）。
#
#   夹具要点：测点坐标就是 V19 迁移里的原值（P-HK01 与一个"山脊背面"的点）。
#   "山脊背面"那个点由高程场解算得出（本地 -400,-300，海拔 60.301m），
#   从北侧雷达看过去整条视线穿地，净空 -75.146m。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

TOKEN=$(login)
AUTH="Authorization: Bearer $TOKEN"
OP_TOKEN=$(login_as operator)
OP_AUTH="Authorization: Bearer $OP_TOKEN"
[ -n "$OP_TOKEN" ] || { printf '%s未能登录 operator%s\n' "$C_RED" "$C_OFF" >&2; exit 2; }

# radar-001 / radar-002 是 V19 播种的演示雷达，id 不写死：换库/重建后仍要能用
DEVICE_ID=$(curl -s "$BASE/devices" -H "$AUTH" | python3 -c "
import sys, json
rs = json.load(sys.stdin)['data']
print(next((r['id'] for r in rs if r['code'] == 'radar-001'), ''))
")
if [ -z "$DEVICE_ID" ]; then
  printf '%s找不到演示雷达 radar-001，本套件依赖 V19 播种的四个场景数据%s\n' "$C_RED" "$C_OFF" >&2
  exit 2
fi

# check_near <描述> <期望> <实得> <容差>
#
# 为什么这套件要带容差：迁移里的角度/斜距/净空是生成器用**精确本地坐标**算的，
# 而档案里存的是**四舍五入到 7 位小数**的经纬度——后端从经纬度反算本地坐标时，
# 必然带进厘米级的还原误差（实测：方位 +0.001°、斜距 +0.009m、净空 +0.001m）。
# 这是往返转换的固有代价，不是算法不一致；用等值断言只会把这条固有误差当成故障。
check_near() {
  local ok
  ok=$(python3 -c "
e, a, t = float(\"$2\"), float(\"$3\"), float(\"$4\")
print('true' if abs(e - a) <= t else 'false')
")
  if [ "$ok" = "true" ]; then pass "$1"; else fail "$1" "期望 [$2] 实得 [$3]（容差 $4）"; fi
}

# 后端 message 字段（错误原因就写在这里）
msg_of() { python3 -c "import sys, json; print(json.load(sys.stdin).get('message', ''))"; }

preview() { # preview <deviceId> <pointId> [body]
  local body="${3:-}"
  [ -n "$body" ] || body='{}'
  curl -s -X POST "$BASE/devices/$1/points/$2/calibration/preview" -H "$AUTH" -H "$JSON" -d "$body"
}

bind_field() { # bind_field <deviceId> <pointId> <字段>
  curl -s "$BASE/devices/$1/points" -H "$AUTH" | python3 -c "
import sys, json
rows = json.load(sys.stdin)['data']
row = next((r for r in rows if r['pointId'] == $2), None)
v = None if row is None else row.get('$3')
print('None' if v is None else v)
"
}

# P-HK01 是 V19 播种的山脊测点（id=1）：坐标与迁移里逐字一致
HK01=1

section "① 试算复现迁移里那条视线（radar-001 → P-HK01）"
PV=$(preview "$DEVICE_ID" "$HK01")
check "结论为通视" "VISIBLE" "$(printf '%s' "$PV" | data_of "['verdict']")"
check_near "最小净空与迁移一致（1.438m）" "1.438" "$(printf '%s' "$PV" | data_of "['minimumClearanceM']")" "0.02"
check_near "方位角与迁移一致（251.565°）" "251.565" "$(printf '%s' "$PV" | data_of "['computedAzimuthDegrees']")" "0.02"
check_near "俯仰角与迁移一致（13.039°）" "13.039" "$(printf '%s' "$PV" | data_of "['computedElevationDegrees']")" "0.02"
check_near "斜距与迁移一致（324.597m）" "324.597" "$(printf '%s' "$PV" | data_of "['computedSlantRangeM']")" "0.02"
check "带出资产版本（口径留痕）" "qingyuan-hillside-2.0.0" "$(printf '%s' "$PV" | data_of "['assetVersion']")"
check "量程/水平/垂直视场三项都为 true" "True True True" \
  "$(printf '%s' "$PV" | python3 -c "import sys,json;d=json.load(sys.stdin)['data'];print(d['withinDetectionRange'],d['withinHorizontalFov'],d['withinVerticalFov'])")"
check "带出步进采样次数（口径留痕，且说明这是逐米算出来的）" "true" \
  "$(printf '%s' "$PV" | python3 -c "import sys,json;print(str(json.load(sys.stdin)['data']['samplingSteps'] > 100).lower())")"

section "② 试算不写库（用一个刚绑上、还没标定的临时测点来证）"
# P-HK01 是 V19 播种的**已生效**标定（ACTIVE），拿它证明不了"试算不写"——
# 所以另建一个与 P-HK01 同坐标的临时测点：绑上就是 PENDING，试算完必须还是 PENDING。
VIS="P-LOS-VIS-$RUN_ID"
VIS_PID=$(curl -s -X POST "$BASE/points" -H "$AUTH" -H "$JSON" \
  -d "{\"objectId\":1,\"code\":\"$VIS\",\"name\":\"通视验收：与山脊测点1同坐标\",
       \"type\":\"POINT_DEFORMATION\",\"longitude\":113.0498585,\"latitude\":23.7612658,
       \"altitude\":211.734,\"enabled\":true}" | data_of "['id']")
check "绑定成功（新绑定是 PENDING）" "200" "$(http_code -X POST "$BASE/devices/$DEVICE_ID/points/$VIS_PID" -H "$AUTH")"
check "（前置）绑定是 PENDING" "PENDING" "$(bind_field "$DEVICE_ID" "$VIS_PID" calibrationStatus)"
PVV=$(preview "$DEVICE_ID" "$VIS_PID")
check "同坐标的临时测点试算同样通视" "VISIBLE" "$(printf '%s' "$PVV" | data_of "['verdict']")"
check_near "净空同为 1.438m" "1.438" "$(printf '%s' "$PVV" | data_of "['minimumClearanceM']")" "0.05"
check "没绑定过标定值时不带出对比字段" "None" "$(printf '%s' "$PVV" | data_of "['boundAzimuthDegrees']")"
check "算完绑定仍是 PENDING" "PENDING" "$(bind_field "$DEVICE_ID" "$VIS_PID" calibrationStatus)"
check "算完绑定仍是 lineOfSight=false" "False" "$(bind_field "$DEVICE_ID" "$VIS_PID" lineOfSight)"
check "算完绑定仍没有标定时间" "None" "$(bind_field "$DEVICE_ID" "$VIS_PID" calibratedAt)"

section "③ 天线高可以覆盖试算（抬到 20m）"
PV20=$(preview "$DEVICE_ID" "$HK01" '{"antennaHeightM":20.0}')
check "回显本次用的天线高" "20.0" "$(printf '%s' "$PV20" | data_of "['antennaHeightUsedM']")"
check_near "净空变成 2.185m（不是 11.438——遮挡点远，抬高只赚一点）" "2.185" \
  "$(printf '%s' "$PV20" | data_of "['minimumClearanceM']")" "0.05"
check "反射器高缺省取生成器的 2.5m" "2.5" "$(printf '%s' "$PV20" | data_of "['reflectorHeightUsedM']")"

section "④ 真被遮挡的目标：试算判 BLOCKED，提交被拒并留痕"
HIDDEN="P-LOS-HIDDEN-$RUN_ID"
HID_PID=$(curl -s -X POST "$BASE/points" -H "$AUTH" -H "$JSON" \
  -d "{\"objectId\":1,\"code\":\"$HIDDEN\",\"name\":\"通视验收：山脊背面点\",
       \"type\":\"POINT_DEFORMATION\",\"longitude\":113.0474061,\"latitude\":23.7567513,
       \"altitude\":60.301,\"enabled\":true}" | data_of "['id']")
info "临时测点 pointId=$HID_PID（$HIDDEN，海拔 60.301m）"
check "绑定成功" "200" "$(http_code -X POST "$BASE/devices/$DEVICE_ID/points/$HID_PID" -H "$AUTH")"
PVH=$(preview "$DEVICE_ID" "$HID_PID")
check "试算结论为被遮挡" "BLOCKED" "$(printf '%s' "$PVH" | data_of "['verdict']")"
check "净空为负（-75.146m，整条视线穿地）" "-75.146" "$(printf '%s' "$PVH" | data_of "['minimumClearanceM']")"
# 量程/FOV 都过得去（方位 226° 在雷达 226.683° 航向的 ±30° 内、俯仰 0° 在 ±15° 内、斜距 100m）
CAL_HIDDEN='{"targetCode":"TGT-LOS-'$RUN_ID'","azimuthDegrees":226.0,"elevationDegrees":0.0,
  "slantRangeM":100.0,"reflectorHeightM":2.5,"lineOfSight":true,"note":"通视验收：故意声称通视"}'
RESP=$(curl -s -X PUT "$BASE/devices/$DEVICE_ID/points/$HID_PID/calibration" -H "$AUTH" -H "$JSON" -d "$CAL_HIDDEN")
check "声称通视 -> 400（地形复核拒收）" "400" \
  "$(http_code -X PUT "$BASE/devices/$DEVICE_ID/points/$HID_PID/calibration" -H "$AUTH" -H "$JSON" -d "$CAL_HIDDEN")"
check_contains "拒收理由说清了是遮挡" "遮挡" "$(printf '%s' "$RESP" | msg_of)"
check_contains "拒收理由带上了最小净空" "-75.15m" "$(printf '%s' "$RESP" | msg_of)"
check_contains "拒收理由带上了资产版本" "qingyuan-hillside-2.0.0" "$(printf '%s' "$RESP" | msg_of)"
check "拒收不改档案：仍然是 PENDING" "PENDING" "$(bind_field "$DEVICE_ID" "$HID_PID" calibrationStatus)"
check "拒收不改档案：lineOfSight 仍是 false" "False" "$(bind_field "$DEVICE_ID" "$HID_PID" lineOfSight)"

section "⑤ 模型范围外：不给结论，也不拦标定"
FAR="P-LOS-FAR-$RUN_ID"
FAR_PID=$(curl -s -X POST "$BASE/points" -H "$AUTH" -H "$JSON" \
  -d "{\"objectId\":1,\"code\":\"$FAR\",\"name\":\"通视验收：模型范围外点\",
       \"type\":\"POINT_DEFORMATION\",\"longitude\":113.0807591,\"latitude\":23.7594600,
       \"altitude\":120.000,\"enabled\":true}" | data_of "['id']")
check "绑定成功" "200" "$(http_code -X POST "$BASE/devices/$DEVICE_ID/points/$FAR_PID" -H "$AUTH")"
PVF=$(preview "$DEVICE_ID" "$FAR_PID")
check "试算结论为超出模型范围" "OUTSIDE_MODEL" "$(printf '%s' "$PVF" | data_of "['verdict']")"
check "范围外不给净空（null，而不是夹到边界的数）" "None" "$(printf '%s' "$PVF" | data_of "['minimumClearanceM']")"
check "但标定照常受理（模型范围外没有发言权）" "200" \
  "$(http_code -X PUT "$BASE/devices/$DEVICE_ID/points/$FAR_PID/calibration" -H "$AUTH" -H "$JSON" \
     -d '{"targetCode":"TGT-LOS-FAR-'$RUN_ID'","azimuthDegrees":226.0,"elevationDegrees":0.0,
          "slantRangeM":100.0,"reflectorHeightM":2.5,"lineOfSight":true,"note":"通视验收：范围外"}')"
check "该绑定确实变成 ACTIVE" "ACTIVE" "$(bind_field "$DEVICE_ID" "$FAR_PID" calibrationStatus)"

section "⑥ 权限与可见性：与读测点的口径一致"
check "值班员试算本项目测点 -> 200" "200" \
  "$(http_code -X POST "$BASE/devices/$DEVICE_ID/points/$HK01/calibration/preview" -H "$OP_AUTH" -H "$JSON" -d '{}')"
check "值班员试算范围外项目的测点：与读测点同码" \
  "$(http_code "$BASE/points/10" -H "$OP_AUTH")" \
  "$(http_code -X POST "$BASE/devices/$DEVICE_ID/points/10/calibration/preview" -H "$OP_AUTH" -H "$JSON" -d '{}')"
check "设备不存在 -> 404" "404" \
  "$(http_code -X POST "$BASE/devices/99999/points/$HK01/calibration/preview" -H "$AUTH" -H "$JSON" -d '{}')"
check "测点不存在 -> 404" "404" \
  "$(http_code -X POST "$BASE/devices/$DEVICE_ID/points/99999/calibration/preview" -H "$AUTH" -H "$JSON" -d '{}')"
check "试算请求体可以整个省略" "200" \
  "$(http_code -X POST "$BASE/devices/$DEVICE_ID/points/$HK01/calibration/preview" -H "$AUTH")"

section "⑦ 地形裁剪覆盖（P1-11 后半）：扇面外缘跟着山脊走"
COV=$(curl -s "$BASE/devices/$DEVICE_ID/coverage" -H "$AUTH")
check "有高程场" "True" "$(printf '%s' "$COV" | data_of "['terrainAvailable']")"
check "资产版本带出" "qingyuan-hillside-2.0.0" "$(printf '%s' "$COV" | data_of "['assetVersion']")"
check "±30° 每 1° 一条 = 61 条方位线" "61" \
  "$(printf '%s' "$COV" | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']['rays']))")"
check "首尾方位线落在视场边界上" "196.683 256.683" \
  "$(printf '%s' "$COV" | python3 -c "import sys,json;r=json.load(sys.stdin)['data']['rays'];print(r[0]['azimuthDegrees'],r[-1]['azimuthDegrees'])")"
check "北侧雷达被地形截短（没有一路看到 620m）" "True" \
  "$(printf '%s' "$COV" | data_of "['terrainClipped']")"
check "最短可见距离小于量程" "true" \
  "$(printf '%s' "$COV" | python3 -c "import sys,json;d=json.load(sys.stdin)['data'];print(str(d['shortestVisibleDistanceM'] < d['detectionRangeM']).lower())")"
check_contains "口径说明写明是按地形裁剪" "地形裁剪" "$(printf '%s' "$COV" | data_of "['note']")"

# 最重要的一条：**扇面与逐目标结论不能互相矛盾**。目标是按"逐米步进视线"核验的，
# 扇面是按"地面仰角 vs 前缀最大仰角"裁的——两套判据独立，正好可以互证：
# 每个已核验目标到雷达的距离，必须落在它所在方位的可见距离之内（方位线 1° 采样，给 30m 余量）。
FAN_CHECK=$(python3 - "$TOKEN" "$BASE" "$DEVICE_ID" <<'PYFAN'
import json, sys, urllib.request
token, base, did = sys.argv[1], sys.argv[2], sys.argv[3]
def get(path):
    req = urllib.request.Request(base + path, headers={'Authorization': 'Bearer ' + token})
    return json.load(urllib.request.urlopen(req))['data']
cov = get(f'/devices/{did}/coverage')
bindings = [b for b in get(f'/devices/{did}/points') if b.get('lineOfSight')]
bad = []
for b in bindings:
    az, d = float(b['azimuthDegrees']), float(b['slantRangeM'])
    ray = min(cov['rays'], key=lambda r: min(abs(r['azimuthDegrees'] - az),
                                             360 - abs(r['azimuthDegrees'] - az)))
    if d > ray['visibleDistanceM'] + 30:
        bad.append('%s 斜距%.1f 但 %s° 只看到 %.1f' % (b['targetCode'], d,
                                                  ray['azimuthDegrees'], ray['visibleDistanceM']))
if not bindings:
    print('没有参与核验的目标（断言不能空过）')
elif bad:
    print('矛盾: ' + '; '.join(bad))
else:
    print('ok(%d)' % len(bindings))
PYFAN
)
# 断言写成"以 ok( 开头"而不是钉死条数：条数取决于当时有几条已生效标定
# （套件会临时加绑定），钉死数字等于把断言挂到别处去。
check_contains "每个已核验目标都落在裁剪扇面之内" "ok(" "$FAN_CHECK"

# 未绑任何测点的设备（看不到数字孪生项目）-> terrainAvailable=false 且带原因
NODEV=$(curl -s -X POST "$BASE/devices" -H "$AUTH" -H "$JSON" \
  -d "{\"code\":\"dev-cov-$RUN_ID\",\"name\":\"覆盖验收：未绑定设备\",\"type\":\"MILLIMETER_WAVE_RADAR\",
       \"longitude\":113.0513300,\"latitude\":23.7594600,\"altitude\":150.000,
       \"antennaHeightM\":10.000,\"headingDegrees\":180,\"pitchDegrees\":0,
       \"detectionRangeM\":300.000,\"halfAngleDegrees\":30.0000,\"verticalHalfAngleDegrees\":15.0000}" \
  | data_of "['id']")
COVN=$(curl -s "$BASE/devices/$NODEV/coverage" -H "$AUTH")
check "未绑定设备的覆盖：terrainAvailable=false" "False" "$(printf '%s' "$COVN" | data_of "['terrainAvailable']")"
check "并给出原因（不静默）" "true" \
  "$(printf '%s' "$COVN" | python3 -c "import sys,json;print(str(bool(json.load(sys.stdin)['data'].get('unavailableReason'))).lower())")"
check "没有几何时 rays 是空的" "0" \
  "$(printf '%s' "$COVN" | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']['rays']))")"
check "设备不存在 -> 404" "404" "$(http_code "$BASE/devices/99999/coverage" -H "$AUTH")"
curl -s -o /dev/null -X DELETE "$BASE/devices/$NODEV" -H "$AUTH"

section "⑦ 回收临时数据"
for pid in "$VIS_PID" "$HID_PID" "$FAR_PID"; do
  curl -s -o /dev/null -X DELETE "$BASE/devices/$DEVICE_ID/points/$pid" -H "$AUTH"
  curl -s -o /dev/null -X DELETE "$BASE/points/$pid" -H "$AUTH"
done
info "已回收三个临时测点（绑定随之解绑）"

summary
