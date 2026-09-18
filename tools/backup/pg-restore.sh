#!/bin/bash
# PostgreSQL 恢复（对应验收第 8 条：备份恢复）。
#
#   tools/backup/pg-restore.sh backups/monitor-20260914-120000.sql
#   tools/backup/pg-restore.sh --latest              # 用 backups/ 里最新的那份
#   tools/backup/pg-restore.sh --with-media backups/media-20260914-120000.tgz
#   tools/backup/pg-restore.sh --yes <文件>          # 不问，直接覆盖（给脚本用）
#
# **这是破坏性操作**：先把 public schema 整个丢掉再灌，库里现有的东西一律没了。
# 所以默认要交互确认，只有显式 --yes 才跳过——验收脚本/CI 走 --yes，人手敲就该被拦住。
#
# 库名/用户从 .env 读，理由同 pg-backup.sh（写死会去连另一个库，而这次是「清空」，
# 认错库的代价比备份时大得多）。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

if [ -f "$ROOT/.env" ]; then
  set -a; . "$ROOT/.env"; set +a
fi
PG_DB="${PG_DB:-monitor}"
PG_USER="${PG_USER:-monitor}"

if [ -t 1 ]; then C_RED=$'\033[31m'; C_GRN=$'\033[32m'; C_YEL=$'\033[33m'; C_DIM=$'\033[2m'; C_OFF=$'\033[0m'
else C_RED=; C_GRN=; C_YEL=; C_DIM=; C_OFF=; fi
die() { printf '%s%s%s\n' "$C_RED" "$1" "$C_OFF" >&2; exit 1; }

SQL=""; MEDIA=""; ASSUME_YES=0
while [ $# -gt 0 ]; do
  case "$1" in
    --yes|-y) ASSUME_YES=1 ;;
    --latest) SQL=$(ls -1t "$ROOT"/backups/monitor-*.sql 2>/dev/null | head -1) ;;
    --with-media) MEDIA="${2:-}"; shift ;;
    -*) die "未知参数：$1" ;;
    *) SQL="$1" ;;
  esac
  shift
done

[ -n "$SQL" ] || die "用法：pg-restore.sh [--yes] [--latest] <备份.sql> [--with-media <附件.tgz>]"
[ -f "$SQL" ] || die "找不到备份文件：$SQL"
[ -s "$SQL" ] || die "备份文件是空的：$SQL"

docker compose ps db --status running --format '{{.Name}}' 2>/dev/null | grep -q . \
  || die "db 服务没在跑。先 docker compose up -d db"

# 后端在跑就先停掉：DROP SCHEMA 要拿所有对象的排他锁，而后端那几条长连接
# 随时可能在查询，撞上就是「锁等查询、查询等锁」——表现为恢复卡死。
# 宁可多停一次容器，也不要一个会偶发卡住的恢复流程。
BACKEND_WAS_UP=0
if docker compose ps backend --status running --format '{{.Name}}' 2>/dev/null | grep -q .; then
  BACKEND_WAS_UP=1
fi

printf '%s即将把库 %s 恢复成 %s%s\n' "$C_YEL" "$PG_DB" "$SQL" "$C_OFF"
printf '%s  库里现有的全部数据会被删除（public schema 整个重建）%s\n' "$C_YEL" "$C_OFF"
[ "$BACKEND_WAS_UP" = 1 ] && printf '  后端容器会先停掉，恢复完自动拉起\n'

if [ "$ASSUME_YES" != 1 ]; then
  printf '确认请输入 yes：'
  read -r ans
  [ "$ans" = "yes" ] || die "已取消，什么都没做"
fi

if [ "$BACKEND_WAS_UP" = 1 ]; then
  docker compose stop backend >/dev/null
  printf '  后端已停\n'
fi

printf '%s  重建 schema（丢弃现有数据）...%s\n' "$C_DIM" "$C_OFF"
# 先踢掉残留连接：上一步 stop 之后可能还有几秒没断干净的会话占着锁
docker compose exec -T db psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 -q \
  -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity
      WHERE datname = current_database() AND pid <> pg_backend_pid();" >/dev/null
docker compose exec -T db psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 -q \
  -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"

printf '%s  灌入备份...%s\n' "$C_DIM" "$C_OFF"
# ON_ERROR_STOP=1 很要紧：psql 默认遇错继续，于是一个中途失败的恢复会「跑完并返回成功」，
# 你以为恢复了、其实只灌了一半。
docker compose exec -T db psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 -q < "$SQL"

TABLES=$(docker compose exec -T db psql -U "$PG_USER" -d "$PG_DB" -tAc \
  "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'")
printf '  恢复完成：public schema 现有 %s 张表\n' "$TABLES"
[ "$TABLES" -gt 0 ] || die "恢复后一张表都没有，备份文件多半不对"

if [ -n "$MEDIA" ]; then
  [ -f "$MEDIA" ] || die "找不到附件包：$MEDIA"
  CFG=$(docker compose config --format json)
  PROJECT=$(printf '%s' "$CFG" | python3 -c "import sys,json;print(json.load(sys.stdin)['name'])")
  IMAGE=$(printf '%s' "$CFG" | python3 -c "import sys,json;print(json.load(sys.stdin)['services']['db']['image'])")
  VOL="${PROJECT}_media"
  docker volume inspect "$VOL" >/dev/null 2>&1 || die "卷 $VOL 不存在"
  # 先清空卷再解开：不这么做的话，附件包里已删的文件会作为「残留」留下来，
  # 卷就成了「本次备份 ∪ 历史全部」——和库里的行对不上，排查时最费神的那种不一致。
  docker run --rm -v "$VOL":/data -v "$(cd "$(dirname "$MEDIA")" && pwd)":/in:ro "$IMAGE" \
      sh -c "rm -rf /data/* && tar xzf /in/$(basename "$MEDIA") -C /data"
  printf '  附件已恢复：%s -> 卷 %s\n' "$MEDIA" "$VOL"
fi

if [ "$BACKEND_WAS_UP" = 1 ]; then
  docker compose start backend >/dev/null
  # 必须等到真的健康再收工。**这不是保险起见**：后端起来时 Flyway 会核对
  # flyway_schema_history 与当前代码——备份来自旧版本、或迁移没跑完，这里就会失败。
  # 只 start 不等待的话，脚本会在一分钟后端还没起来时就报「恢复完成」，
  # 调用方紧接着打接口只会拿到 connection refused，然后把「后端没起来」误当成
  # 「数据没恢复」（这个坑自测脚本第一次跑就踩了：4 条接口断言全红，而库里其实好好的）。
  printf '%s  等后端起来（Flyway 会核对迁移版本，备份与当前代码不一致时这里会失败）...%s\n' "$C_DIM" "$C_OFF"
  READY=0
  for _ in $(seq 1 90); do
    curl -sf "http://localhost:${BACKEND_PORT:-8080}/api/v1/health" >/dev/null 2>&1 && { READY=1; break; }
    sleep 1
  done
  if [ "$READY" = 1 ]; then
    printf '  后端已就绪\n'
  else
    printf '%s  后端 90s 内没起来。恢复本身已完成，但应用起不来说明有问题，看：%s\n' "$C_RED" "$C_OFF" >&2
    docker compose logs --tail 30 backend >&2
    exit 1
  fi
fi

printf '%s恢复完成%s\n' "$C_GRN" "$C_OFF"
