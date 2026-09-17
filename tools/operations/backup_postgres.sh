#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
backup_dir="${1:-${project_root}/backups}"
compose_file="${project_root}/docker-compose.production.yml"
env_file="${project_root}/.env.production"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
target="${backup_dir}/monitor-${stamp}.sql.gz"
partial="${target}.partial"

install -d -m 700 "${backup_dir}"
docker compose --env-file "${env_file}" -f "${compose_file}" exec -T db \
  sh -c 'pg_dump --clean --if-exists --no-owner --no-privileges -U "$POSTGRES_USER" "$POSTGRES_DB"' \
  | gzip -9 > "${partial}"
gzip -t "${partial}"
mv "${partial}" "${target}"
sha256sum "${target}" > "${target}.sha256"
chmod 600 "${target}" "${target}.sha256"
printf '%s\n' "backup=${target}"
