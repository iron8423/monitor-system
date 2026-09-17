#!/bin/bash
# 备份恢复实测（验收第 8 条「备份恢复」的落点）。
#
#   docker compose up -d
#   tools/backup/selftest.sh
#
# 验收第 8 条的原话是「**恢复后**查得到历史/告警/附件」——所以这里不能只验「命令没报错」，
# 要真的把库改坏、再用备份救回来，然后逐样查一遍。做法：
#   ① 先往演示库挂一张真附件（附件在卷里，不在库里，是这条验收单独的一半）
#   ② 记下基线：measurement / alarm 条数与一条具体的告警 id（走 psql 直连库数全库 / 取行）+
#      测点 1 的附件条数（走 API，按测点列）
#   ③ 备份（库 + 附件卷）
#   ④ **破坏**：直接进 PG 灌一条假项目、删掉那条真实告警、删掉附件文件
#      —— 走 psql 不走 API：API 会带业务校验和逻辑删除，破坏得不够彻底
#   ⑤ 恢复
#   ⑥ 逐样查回来：假项目没了、被删的告警回来了、条数回到基线、附件还能读出图
#
# 与其它套件的分工：其余套件都跑在 H2 空库上、不碰 docker；这一条必须跑在 compose 的
# PostgreSQL 上（备份恢复是 PG 的事），所以不并进 run-all.sh，单独跑。
# 依赖 compose 起着，且**会改动演示库**（最后恢复回备份那一刻的状态）。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

# 复用验收套件的公共库：登录、断言、计数口径都与其它套件一致，不另起一套
source "$HERE/../acceptance/lib.sh"

BACKUP_SH="$HERE/pg-backup.sh"
RESTORE_SH="$HERE/pg-restore.sh"

section "⓪ 前置：compose 起着，后端可达"
if ! curl -sf "$BASE/health" >/dev/null 2>&1; then
  fail "后端不可达 $BASE" "先 docker compose up -d，再跑本脚本"
  summary
fi
pass "后端可达 $BASE"
TOK=$(login)
AUTH="Authorization: Bearer $TOK"

# 库名/用户：与两个脚本同源（.env 优先，否则 compose 默认）
if [ -f "$ROOT/.env" ]; then set -a; . "$ROOT/.env"; set +a; fi
PG_DB="${PG_DB:-monitor}"; PG_USER="${PG_USER:-monitor}"
psql_c() { docker compose -f "$ROOT/docker-compose.yml" exec -T db psql -U "$PG_USER" -d "$PG_DB" -tAc "$1"; }

section "① 挂一张真附件（附件在卷里，不在库里）"
PNG=/tmp/selftest-1x1.png
python3 - "$PNG" <<'PY'
import base64, sys
# 1×1 透明 PNG，够小、又是真图（读回来能验魔数）
open(sys.argv[1], 'wb').write(base64.b64decode(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8AAAwAB/AGN9ScAAAAASUVORK5CYII='))
PY
UP=$(curl -s -X POST "$BASE/media" -H "$AUTH" \
     -F "file=@$PNG;type=image/png" -F "pointId=1" -F "note=备份恢复自测附件")
MEDIA_ID=$(printf '%s' "$UP" | data_of "['mediaId']")
check_contains "附件已挂到测点 1" "备份恢复自测附件" \
  "$(curl -s "$BASE/points/1/media" -H "$AUTH" | python3 -c "
import sys,json
rs=json.load(sys.stdin)['data']
print([m['note'] for m in rs])")"
check "附件内容可读（恢复前基线）" "200" "$(http_code "$BASE/media/$MEDIA_ID/content?token=$TOK")"

section "② 记基线（附件数走 API 按测点列，与 06 套件口径一致；measurement / alarm 条数与取样告警 id 直接 psql 连库数——measurement 没有全库计数的接口口径，series 是按测点返回的）"
# 附件数按测点列，不按全库：这个卷里可能还有别的套件留下的图，全库数会随它们漂移
N_MEDIA=$(curl -s "$BASE/points/1/media" -H "$AUTH" | python3 -c "
import sys,json;print(len(json.load(sys.stdin)['data']))")
N_MEAS=$(psql_c "SELECT count(*) FROM measurement")
ALARM_ID=$(psql_c "SELECT id FROM alarm ORDER BY id LIMIT 1")
ALARM_CNT_SQL=$(psql_c "SELECT count(*) FROM alarm")
info "基线：measurement=$N_MEAS alarm=$ALARM_CNT_SQL 测点1附件=$N_MEDIA 取样告警 id=$ALARM_ID"
[ -n "$ALARM_ID" ] || { fail "库里一条告警都没有" "先在演示库造点数据再跑（附件已挂上，可继续）"; summary; }

section "③ 备份（库 + 附件卷）"
OUT_DIR=$(mktemp -d)
if OUT_DIR="$OUT_DIR" "$BACKUP_SH" --with-media > /tmp/selftest-backup.log 2>&1; then
  pass "pg-backup.sh --with-media 成功"
else
  fail "pg-backup.sh 失败" "$(tail -5 /tmp/selftest-backup.log)"
  summary
fi
SQL=$(ls -1t "$OUT_DIR"/monitor-*.sql | head -1)
TGZ=$(ls -1t "$OUT_DIR"/media-*.tgz | head -1)
check "备份文件非空" "True" "$([ -s "$SQL" ] && [ -s "$TGZ" ] && echo True || echo False)"
info "SQL $(du -h "$SQL" | cut -f1) / 附件 $(du -h "$TGZ" | cut -f1)"

section "④ 破坏：灌假数据 + 删真告警 + 删附件文件"
psql_c "INSERT INTO project (code, name, deleted, created_at, updated_at)
        VALUES ('BOGUS-RESTORE-TEST', '恢复测试假项目', 0, now(), now())" >/dev/null
psql_c "DELETE FROM alarm_action WHERE alarm_id = $ALARM_ID" >/dev/null
psql_c "DELETE FROM alarm WHERE id = $ALARM_ID" >/dev/null
check "假项目已灌入" "1" "$(psql_c "SELECT count(*) FROM project WHERE code='BOGUS-RESTORE-TEST'")"
check "真告警已删除" "0" "$(psql_c "SELECT count(*) FROM alarm WHERE id=$ALARM_ID")"

# 附件的**文件**也要一起破坏，否则「--with-media」这一半是白验的：
# 只恢复库的话，media 行回来了、文件却没有，接口列表看着正常、点开图是碎的——
# 这正是「只备库」的坑，而它只在真删过文件之后才暴露得出来。
# 文件在 compose 的 media 卷里（不是仓库目录下那个 backend/data/media，那是非容器模式用的）。
CFG=$(docker compose -f "$ROOT/docker-compose.yml" config --format json)
PROJECT=$(printf '%s' "$CFG" | python3 -c "import sys,json;print(json.load(sys.stdin)['name'])")
IMAGE=$(printf '%s' "$CFG" | python3 -c "import sys,json;print(json.load(sys.stdin)['services']['db']['image'])")
MEDIA_VOL="${PROJECT}_media"
docker run --rm -v "$MEDIA_VOL":/data "$IMAGE" sh -c 'rm -rf /data/*'
check "附件文件已清空（卷里）" "0" \
  "$(docker run --rm -v "$MEDIA_VOL":/data "$IMAGE" sh -c 'find /data -type f | wc -l' | tr -d ' ')"

section "⑤ 恢复"
if "$RESTORE_SH" --yes --with-media "$TGZ" "$SQL" > /tmp/selftest-restore.log 2>&1; then
  pass "pg-restore.sh 成功"
else
  fail "pg-restore.sh 失败" "$(tail -8 /tmp/selftest-restore.log)"
  info "备份留在 $OUT_DIR（未删，便于排查）"
  summary
fi

section "⑥ 逐样查回来（验收第 8 条的原话：历史 / 告警 / 附件）"
check "假项目没了" "0" "$(psql_c "SELECT count(*) FROM project WHERE code='BOGUS-RESTORE-TEST'")"
check "被删的告警回来了" "1" "$(psql_c "SELECT count(*) FROM alarm WHERE id=$ALARM_ID")"
check "告警总数回到基线" "$ALARM_CNT_SQL" "$(psql_c "SELECT count(*) FROM alarm")"
check "历史数据（measurement）回到基线" "$N_MEAS" "$(psql_c "SELECT count(*) FROM measurement")"
# 走 API 而不是 SQL：验收第 8 条要的是「查得到」，即业务接口读得出来，不只是行还在
check "告警经 API 查得到" "$ALARM_ID" \
  "$(curl -s "$BASE/alarms/$ALARM_ID" -H "$AUTH" | data_of "['id']")"
check "附件经 API 列得出" "$N_MEDIA" \
  "$(curl -s "$BASE/points/1/media" -H "$AUTH" | python3 -c "
import sys,json;print(len(json.load(sys.stdin)['data']))")"

# 附件内容：拿得到 200 且是 PNG 魔数。这一步才真的证明「卷里的文件回来了」——
# 上面的列表只证明 media **行**回来了，而文件是另一处存储。
CT=$(curl -s "$BASE/media/$MEDIA_ID/content?token=$TOK" -o /tmp/selftest-back.png -w '%{http_code}')
check "附件内容 HTTP 200" "200" "$CT"
check "读回的是真 PNG（不是空文件/错误页）" "True" "$(python3 -c "
d=open('/tmp/selftest-back.png','rb').read()
print(d[:8]==b'\x89PNG\r\n\x1a\n')" 2>/dev/null || echo False)"

section "⑦ 清场"
curl -s -o /dev/null -X DELETE "$BASE/media/$MEDIA_ID" -H "$AUTH"
info "自测附件已删（软删，文件按设计留在卷里）"
info "备份文件留在 $OUT_DIR，可自行清理"

summary
