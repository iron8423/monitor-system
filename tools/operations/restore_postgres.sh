#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" != "--confirm-empty-target" || -z "${2:-}" ]]; then
  printf '%s\n' "用法: $0 --confirm-empty-target <backup.sql.gz>" >&2
  printf '%s\n' "仅允许恢复到已确认可覆盖的测试/灾备目标库。" >&2
  exit 2
fi

backup_file="$(realpath "$2")"
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
compose_file="${project_root}/docker-compose.production.yml"
env_file="${project_root}/.env.production"

gzip -t "${backup_file}"
if [[ -f "${backup_file}.sha256" ]]; then
  (cd "$(dirname "${backup_file}")" && sha256sum -c "$(basename "${backup_file}").sha256")
fi

gzip -dc "${backup_file}" | docker compose --env-file "${env_file}" -f "${compose_file}" exec -T db \
  sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" "$POSTGRES_DB"'
printf '%s\n' "restore=completed"
