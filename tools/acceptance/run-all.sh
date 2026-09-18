#!/bin/bash
# 一键跑全部后端验收套件（把「demo 验收脚本」里后端可独立验证的部分收成可重复跑的断言）。
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
# 后端日志留存路径（CI 与排障用）：默认不设 = 跑完就删（临时文件）。
# 为什么需要：脚本原本把日志放 mktemp，失败时只在终端打最后 30 行——
# 而 CI 的失败发生在哪一套、后端当时说了什么，往往要看完整日志才判得出来。
KEEP_LOG="${KEEP_LOG:-}"

# 非 fresh：跑在已经起来的后端上，地址可用 BASE 覆盖
if [ "$FRESH" = 1 ]; then BASE="http://localhost:$PORT/api/v1"
else BASE="${BASE:-http://localhost:8080/api/v1}"; fi

LOG=$(mktemp)
BG_PID=""
# 只有本脚本**真的起过**后端才会置 1。见 cleanup 的注释：trap 在任何出口都会跑，
# 包括「端口被占、拒绝启动」那条路径。
STARTED=0

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
# 只在**本脚本真的起过后端**时才收。此前这里写的是 `[ "$FRESH" = 1 ]`，而 trap 在
# **任何**出口都会跑——包括上面那条「端口已被占用，换 FRESH_PORT 再试」的拒绝路径。
# 于是发生了一件事（2026-09-17 实测复现）：一次被拒绝的 `--fresh` 退出时照样跑
# `pkill -f "server.port=$PORT"`，**把占用该端口的那个后端杀掉了**——正是它刚刚拒绝去打扰的进程。
# 当时的表现是另一份并排跑的验收在 02 中途开始全数 HTTP 000，看起来像代码崩了，其实是被同类杀掉。
#
# 更要紧的是 `pkill -f` 是**全命令行正则匹配**，不限本脚本的进程树：任何命令行里含这个串的
# 进程都会中招。实测把它写在 `bash -c` 里跑，它把**执行它的那个 shell** 也一起杀了
# （`bash -c '... --server.port=18998 ...'` 的命令行自带这个串）。
# 所以这条 pkill 只能在「我们已经起过后端、且准备收掉自己那一个」的语境下用。
cleanup() {
  [ "$STARTED" = 1 ] && stop_backend
  if [ -n "$KEEP_LOG" ]; then
    mkdir -p "$(dirname "$KEEP_LOG")" 2>/dev/null
    cp "$LOG" "$KEEP_LOG" 2>/dev/null || true
  fi
  rm -f "$LOG"
}
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
  STARTED=1     # 从这里往后才允许 cleanup 去收进程（见 stop_backend 上方的注释）

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

# 10-scope 排在最后一段，11/12 那两个造并发的套件排在它之后：
#   10 要断言「admin 与李敏看到的条数之差 == 项目 2 的规模」，虽然用的是差值
#   （不依赖其它套件是否回收干净），但排在最后能少一层噪声；
#   11-concurrency 与 12-ingest-concurrency 是两个**造真并发**的套件（都用后台 curl + wait），
#   各自会新建测点并回收，把它们排在数组末尾（12 是末位，其后没有任何套件），
#   可以保证不会有后来者被它们留下的临时数据影响。
# 注意 11 依赖后端运行在本地：它刻意用后台 curl + wait 造并发，与 BASE 指向哪里无关，
# 但并发度 6 是照 Hikari 默认池 10 定的（见该套件文件头），换池大小要重估。
#
# 13-calibration 排在 10-scope **之前**（数组实序：… 08 → 09 → 13 → 10-scope → 11 → 12，
# 后三者垫底）：它建临时设备/测点并回收，属于「造数」类，与 11/12 同属
# 「会留下临时数据」的一类；但落位与它们**相反**——造并发的两个排在最后，
# 造数的那个排在数据隔离套件之前。
#
# **这个数组是显式的，不是 glob**：新增套件文件不写进来就是**静默永不执行**——
# 汇总里少一个套件、少几十条断言，而输出看不出任何异常。
# 14-password-change.sh **必须是最后一个**：它在一次性实例上会把 admin 的口令轮换掉
# （原因见其文件头），排在它后面等于排在「admin 已换口令」之后——那些套件会全部登录不上，
# 而报错只会显示「后端不可达或登录失败」，离真正的原因很远。
# 15-users 排在 13-calibration 之后、10-scope 之前：它也属于「造数并自己回收」的一类
# （建一个临时账号、验完停用+逻辑删除），与 10-scope 的差值型断言隔开一层，少一层噪声。
# 16-strict-contract 自带一个后端（端口 18099），跑的不是 BASE 指向的那个实例：
# 严格契约在生产编排里是**默认打开**的，而其余套件都跑在 strict=false 上。
# 它自己造数、自己回收，落在数组倒数第三位——只有 14（改口令）必须垫底。
#
# 17-audit 排在 16 之后、14 之前：它也自己造数（临时测点/设备）并回收，
# 但审计行按设计**不回收**（只增不减），所以放在末尾一带，尽量不影响别的套件的计数断言。
SUITES=(01-archive-auth.sh 02-ingest-idempotency.sh 03-query.sh 04-alarm.sh 05-realtime.sh 06-media.sh 07-device-alarm.sh 08-simulator.sh 09-data-quality.sh 13-calibration.sh 15-users.sh 10-scope.sh 11-concurrency.sh 12-ingest-concurrency.sh 16-strict-contract.sh 17-audit.sh 18-login-limit.sh 14-password-change.sh)
TOTAL_PASS=0; TOTAL_FAIL=0; FAILED_SUITES=()

# 后端日志路径：--fresh 时是本脚本自己起的那个进程的输出，可以让套件去 grep 证据行；
# 非 fresh（跑在别人已经起好的后端上）时没有日志可给，套件会退化成打印提示。
BACKEND_LOG=""
[ "$FRESH" = 1 ] && BACKEND_LOG="$LOG"

# 改密套件（14）排在最末尾，且只在**一次性实例**上做真实轮换：
# 新口令强制 ≥8 位，而演示口令是 6 位（123456），所以「轮换后改回去」在 API 上做不到。
# `--fresh` 起的 H2 库随进程消失，轮换无害；对着 compose 那种持久库跑时**不要**打开它，
# 否则 admin 的口令会被真的改掉，下一次谁都登不进来。详见 14-password-change.sh 文件头。
# 注：赋值前缀必须是**字面量**（shell 在展开之前就解析 `VAR=value cmd`），
# 所以这里用数组 + env 传，而不是拼一个 "ALLOW_PASSWORD_ROTATION=1" 字符串再展开——
# 那样写出来的是「命令名 ALLOW_PASSWORD_ROTATION=1」，套件当然跑不起来。
RUN_ENV=(env "BASE=$BASE" "BACKEND_LOG=$BACKEND_LOG")
[ "$FRESH" = 1 ] && RUN_ENV+=("ALLOW_PASSWORD_ROTATION=1")

for s in "${SUITES[@]}"; do
  printf '\n%s======== %s ========%s\n' "$C_DIM" "$s" "$C_OFF"
  OUT=$("${RUN_ENV[@]}" bash "$HERE/$s" 2>&1)
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
[ -n "$KEEP_LOG" ] && printf '  后端日志已留存：%s\n' "$KEEP_LOG"
if [ "$TOTAL_FAIL" -eq 0 ]; then
  printf '  %s全部通过%s\n' "$C_GRN" "$C_OFF"
  exit 0
fi
printf '  %s未通过：%s%s\n' "$C_RED" "${FAILED_SUITES[*]}" "$C_OFF"
exit 1
