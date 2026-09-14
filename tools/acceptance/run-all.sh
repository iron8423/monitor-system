#!/bin/bash
# 一键跑全部后端验收套件（对应《双人分工实施方案v2》§9 验收脚本里后端可独立验证的部分）。
#
#   ./run-all.sh              跑在当前已启动的后端上（默认 http://localhost:8080/api/v1）
#   ./run-all.sh --fresh      先在【空闲端口】起一个全新后端（H2 空库），跑完自动停
#
# 关于 --fresh 为什么另开端口而不是重启 8080：重启 8080 时若旧 JVM 没退干净，
# 新进程会因端口占用而悄悄退出，健康检查却由旧进程应答——于是你在一份陈旧代码/脏库上
# 跑完了整套验收还全绿。换个新端口就不存在这种「看起来起来了」：端口被占则新进程直接启动失败，
# 健康检查永远等不到，脚本会明确报错。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

FRESH=0
[ "${1:-}" = "--fresh" ] && FRESH=1
PORT="${FRESH_PORT:-18080}"
MVNW_OPTS="${MVNW_OPTS:--o}"      # 默认离线（本仓 ~/.m2 已就绪）；无本地仓库时置空走在线

# 非 fresh：跑在已经起来的后端上，地址可用 BASE 覆盖
if [ "$FRESH" = 1 ]; then BASE="http://localhost:$PORT/api/v1"
else BASE="${BASE:-http://localhost:8080/api/v1}"; fi

LOG=$(mktemp)
BG_PID=""

if [ -t 1 ]; then C_RED=$'\033[31m'; C_GRN=$'\033[32m'; C_YEL=$'\033[33m'; C_DIM=$'\033[2m'; C_OFF=$'\033[0m'
else C_RED=; C_GRN=; C_YEL=; C_DIM=; C_OFF=; fi

stop_backend() {
  # setsid 让子进程自成会话/进程组，整组一起收；maven 会再 fork 一个 JVM，
  # 所以再加一条按启动参数匹配的 pkill 兜底
  [ -n "$BG_PID" ] && kill -- "-$BG_PID" 2>/dev/null
  [ -n "$BG_PID" ] && kill "$BG_PID" 2>/dev/null
  pkill -f "server.port=$PORT" 2>/dev/null
  for _ in $(seq 1 40); do
    ss -ltn 2>/dev/null | grep -q ":$PORT " || return 0
    sleep 0.25
  done
  printf '%s端口 %s 在 10s 内未释放，手工确认残留进程%s\n' "$C_YEL" "$PORT" "$C_OFF" >&2
}
cleanup() { [ "$FRESH" = 1 ] && stop_backend; rm -f "$LOG"; }
trap cleanup EXIT

if [ "$FRESH" = 1 ]; then
  if ss -ltn 2>/dev/null | grep -q ":$PORT "; then
    printf '%s端口 %s 已被占用，换 FRESH_PORT=xxxx 再试%s\n' "$C_RED" "$PORT" "$C_OFF" >&2
    exit 2
  fi
  # 跟着空库一起清掉上传目录。
  # 库是 jdbc:h2:mem（进程一停就没了），种子也不含 media 行，所以后端一退出，
  # 本机 data/media 里的文件就**定义上全是孤儿**——拥有它们的那张表已经不存在了。
  # 不清的话每跑一轮 --fresh 就攒一批（06-media.sh 每轮传 3 张 1×1 测试图，
  # 且因为影像改成了**逻辑删除**、盘上文件按设计保留，套件删了行也带不走文件）。
  # 容器形态走的是 compose 的命名卷，不受这里影响。
  rm -rf "$ROOT/backend/data/media"

  printf '%s启动全新后端：端口 %s（H2 内存库，空库）...%s\n' "$C_DIM" "$PORT" "$C_OFF"
  # --monitor.device-offline.sweep-ms=2000：07 套件要等设备离线扫描出结果，
  # 线上默认 10s 也能过（套件按 40s 上限轮询），这里调快纯粹是省时间。
  # --monitor.data-quality.sweep-ms=2000：同理由，09 套件要等数据可信度扫描出结果。
  ( cd "$ROOT/backend" && exec setsid ./mvnw $MVNW_OPTS spring-boot:run \
      -Dspring-boot.run.arguments="--server.port=$PORT --monitor.device-offline.sweep-ms=2000 --monitor.data-quality.sweep-ms=2000" ) > "$LOG" 2>&1 &
  BG_PID=$!

  printf '等待就绪'
  READY=0
  for _ in $(seq 1 120); do
    if curl -sf "$BASE/health" >/dev/null 2>&1; then READY=1; break; fi
    # 启动进程已死就别再等了（端口冲突、编译失败等都会走到这里）
    if ! kill -0 "$BG_PID" 2>/dev/null; then break; fi
    printf '.'; sleep 1
  done
  printf '\n'
  if [ "$READY" != 1 ]; then
    printf '%s后端未就绪，日志尾部：%s\n' "$C_RED" "$C_OFF" >&2
    tail -30 "$LOG" >&2
    exit 2
  fi
  # 确认应答的确实是我们这个新进程（新端口 + 日志里有它自己的启动记录）
  if ! grep -q "Tomcat started on port $PORT" "$LOG" && ! grep -q "Started .*Application" "$LOG"; then
    printf '%s健康检查通了，但启动日志里没有本次进程的记录，疑似别的进程在应答%s\n' "$C_RED" "$C_OFF" >&2
    exit 2
  fi
  printf '%s后端就绪（pid %s）%s\n' "$C_GRN" "$BG_PID" "$C_OFF"
fi

printf '\n%s验收目标：%s%s\n' "$C_DIM" "$BASE" "$C_OFF"

# 10-scope 放最后：它要断言「admin 与李敏看到的条数之差 == 项目 2 的规模」，
# 虽然用的是差值（不依赖其它套件是否回收干净），但排在最后能少一层噪声。
SUITES=(01-archive-auth.sh 02-ingest-idempotency.sh 03-query.sh 04-alarm.sh 05-realtime.sh 06-media.sh 07-device-alarm.sh 08-simulator.sh 09-data-quality.sh 10-scope.sh)
TOTAL_PASS=0; TOTAL_FAIL=0; FAILED_SUITES=()

for s in "${SUITES[@]}"; do
  printf '\n%s======== %s ========%s\n' "$C_DIM" "$s" "$C_OFF"
  OUT=$(BASE="$BASE" bash "$HERE/$s" 2>&1)
  echo "$OUT" | grep -v '^#RESULT'
  RES=$(echo "$OUT" | grep '^#RESULT' | tail -1)
  p=$(echo "$RES" | sed -n 's/.*pass=\([0-9]*\).*/\1/p'); p=${p:-0}
  f=$(echo "$RES" | sed -n 's/.*fail=\([0-9]*\).*/\1/p'); f=${f:-0}
  [ -z "$RES" ] && { f=1; FAILED_SUITES+=("$s（脚本异常退出）"); }
  TOTAL_PASS=$((TOTAL_PASS + p)); TOTAL_FAIL=$((TOTAL_FAIL + f))
  [ "$f" -gt 0 ] && [ -n "$RES" ] && FAILED_SUITES+=("$s")
done

printf '\n%s================ 汇总 ================%s\n' "$C_DIM" "$C_OFF"
if [ "$TOTAL_FAIL" -eq 0 ]; then FC="$C_GRN"; else FC="$C_RED"; fi
printf '  套件 %d 个 · 断言通过 %s%d%s · 失败 %s%d%s\n' \
  "${#SUITES[@]}" "$C_GRN" "$TOTAL_PASS" "$C_OFF" "$FC" "$TOTAL_FAIL" "$C_OFF"
if [ "$TOTAL_FAIL" -eq 0 ]; then
  printf '  %s全部通过%s\n' "$C_GRN" "$C_OFF"
  exit 0
fi
printf '  %s未通过：%s%s\n' "$C_RED" "${FAILED_SUITES[*]}" "$C_OFF"
exit 1
