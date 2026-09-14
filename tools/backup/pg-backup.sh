#!/bin/bash
# PostgreSQL 备份（《双人分工实施方案v2》§9 验收第 8 条：备份恢复）。
#
#   tools/backup/pg-backup.sh                 # 只备数据库 -> backups/monitor-<时间戳>.sql
#   tools/backup/pg-backup.sh --with-media    # 连附件卷一起备 -> 再加 backups/media-<时间戳>.tgz
#   OUT_DIR=/mnt/nas tools/backup/pg-backup.sh
#
# 库名 / 用户 / 项目名**一律从 .env 读**（compose 也是这么读的），读不到才退回 compose 里那份默认值。
# 为什么不写死 monitor/monitor：.env.example 的存在就是为了让人改这几个值，而写死的备份
# 会去连一个别的库——pg_dump 报「库不存在」还算好的，怕的是那个库恰好存在，
# 于是你拿到一份看着成功、实际不相干的备份。
#
# 为什么用 `docker compose exec -T`：exec 默认要分配 TTY，而备份是脚本里跑的、没有 TTY，
# 不加 -T 会得到 "the input device is not a TTY" 或把控制字符混进 .sql 里。
# 这是这类脚本最常见的坑，写在这里省得下次再踩。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

WITH_MEDIA=0
[ "${1:-}" = "--with-media" ] && WITH_MEDIA=1
OUT_DIR="${OUT_DIR:-$ROOT/backups}"

# .env 不存在也能跑：compose 里每个变量都有默认值，这里跟着用同一份
if [ -f "$ROOT/.env" ]; then
  set -a; . "$ROOT/.env"; set +a
fi
PG_DB="${PG_DB:-monitor}"
PG_USER="${PG_USER:-monitor}"

if [ -t 1 ]; then C_RED=$'\033[31m'; C_GRN=$'\033[32m'; C_DIM=$'\033[2m'; C_OFF=$'\033[0m'
else C_RED=; C_GRN=; C_DIM=; C_OFF=; fi
die() { printf '%s%s%s\n' "$C_RED" "$1" "$C_OFF" >&2; exit 1; }

docker compose ps db --status running --format '{{.Name}}' 2>/dev/null | grep -q . \
  || die "db 服务没在跑。先 docker compose up -d db"

mkdir -p "$OUT_DIR"
TS=$(date +%Y%m%d-%H%M%S)
SQL="$OUT_DIR/monitor-$TS.sql"

printf '%s备份数据库 %s（用户 %s）-> %s%s\n' "$C_DIM" "$PG_DB" "$PG_USER" "$SQL" "$C_OFF"
# 不加 --clean：回灌前由 pg-restore.sh 显式重建 schema，这样「清空」只发生在一处，
# 而不是散落在 dump 文件里的一堆 DROP 上（那种 dump 直接 psql 灌进生产库同样会删表）。
docker compose exec -T db pg_dump -U "$PG_USER" -d "$PG_DB" > "$SQL"

# pg_dump 失败时 > 已经建好了空文件，所以按内容判断而不是按退出码
[ -s "$SQL" ] || { rm -f "$SQL"; die "dump 是空的，备份失败"; }
grep -q 'CREATE TABLE' "$SQL" || die "dump 里没有建表语句，内容可疑：$SQL"
printf '  数据库备份完成：%s（%s）\n' "$SQL" "$(du -h "$SQL" | cut -f1)"

if [ "$WITH_MEDIA" = 1 ]; then
  # 附件是**卷**里的文件，不在库里——只备库的话，恢复后 media 表指着的图还在（卷没被动过），
  # 但要换台机器/换套卷重建，缺了这份 tar 就只剩一堆指向空文件的行。
  #
  # 卷名由 compose 项目名派生。项目名在 docker-compose.yml 里写死为 monitor-system，
  # 但 COMPOSE_PROJECT_NAME 和 -p 都能覆盖它，所以向 compose 自己问，不猜。
  CFG=$(docker compose config --format json)
  PROJECT=$(printf '%s' "$CFG" | python3 -c "import sys,json;print(json.load(sys.stdin)['name'])")
  IMAGE=$(printf '%s' "$CFG" | python3 -c "import sys,json;print(json.load(sys.stdin)['services']['db']['image'])")
  VOL="${PROJECT}_media"

  docker volume inspect "$VOL" >/dev/null 2>&1 || die "卷 $VOL 不存在"
  TGZ="$OUT_DIR/media-$TS.tgz"
  # 用 db 那个镜像而不是 alpine：它一定已经在本地了，不必联网再拉一个。
  # :ro 挂数据卷——备份过程不该有写权限，万一 tar 写错地方也伤不到原文件。
  docker run --rm -v "$VOL":/data:ro -v "$OUT_DIR":/out "$IMAGE" \
      tar czf "/out/$(basename "$TGZ")" -C /data .
  printf '  附件备份完成：%s（%s）\n' "$TGZ" "$(du -h "$TGZ" | cut -f1)"
fi

printf '%s备份完成。恢复见 tools/backup/pg-restore.sh%s\n' "$C_GRN" "$C_OFF"
