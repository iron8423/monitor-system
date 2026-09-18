#!/bin/bash
# 四个演示场景的**保活**：每隔一段时间给每个场景补一轮实时数据，让设备一直"在线"。
#
# 用法：
#   tools/radar_simulator/keepalive_all_scenes.sh              # 每 60s 一轮，Ctrl-C 停
#   KEEPALIVE_INTERVAL=30 tools/radar_simulator/keepalive_all_scenes.sh
#   tools/radar_simulator/keepalive_all_scenes.sh --once       # 只跑一轮（自测/手动补数据）
#   BASE=http://localhost:8088/api/v1 ... --once               # 打生产栈
#
# 为什么需要它（复查清单 P0-2 的另一半）：
#   `seed_all_scenes.sh` 是**一次性**播种——灌完历史就退出。而设备在线判据是
#   「5 分钟内收到过数据」（DeviceStatusPolicy.OFFLINE_MINUTES），于是播完 5 分钟，
#   五台雷达会**正确地**一起判离线，告警中心冒出若干条 OFFLINE。
#   那不是 bug：数据确实停了。P0-2 修的是"从未上报的设备被播种成刚刚上报过"，
#   而"播完就停"只能靠保活解决——要么常驻这个脚本，要么接受离线告警。
#
# 默认间隔 60s：远小于 5 分钟的离线窗口，又不会给演示库攒太多数据
# （一轮 = 25 条消息 = 50 行；按分钟算一天约 7 万行，仍是演示量级）。
#
# 只发 REALTIME：补报（BACKFILL）不改在线状态，保活必须是实时模式才有意义。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
BASE="${BASE:-http://localhost:8080/api/v1}"
URL="$BASE/ingest/measurements"
KEY="${INGEST_KEY:-dev-ingest-key}"
INTERVAL="${KEEPALIVE_INTERVAL:-60}"
SIM="$ROOT/tools/radar_simulator/radar_simulator.py"
ONCE=0
[ "${1:-}" = "--once" ] && ONCE=1

if [ ! -f "$SIM" ]; then
  printf '找不到模拟器 %s\n' "$SIM" >&2
  exit 2
fi

# 与 seed_all_scenes.sh 一致的四组点号 + 设备（保活必须打在**同一批点**上，
# 否则设备归属链对不上、在线状态也不会刷新到正确的雷达）
tick() {
  local label="$1" points="$2" device="$3"
  if [ -n "$device" ]; then
    out=$(python3 "$SIM" --url "$URL" --key "$KEY" --points "$points" --device "$device" \
          --count 1 --interval 0 --ingest-mode REALTIME --seed "$RANDOM" 2>&1 | tail -1)
  else
    out=$(python3 "$SIM" --url "$URL" --key "$KEY" --points "$points" \
          --count 1 --interval 0 --ingest-mode REALTIME --seed "$RANDOM" 2>&1 | tail -1)
  fi
  printf '  %-22s %s\n' "$label" "$(printf '%s' "$out" | sed 's/^ *//')"
}

round() {
  printf '[%s] 保活轮次（%s）\n' "$(date +%H:%M:%S)" "$BASE"
  tick "山地边坡" "P-HK01,P-HK02,P-HK03,P-BP01,P-BP02,P-BP03,P-BP04" ""
  tick "野外桥梁" "P-BR01,P-BR02,P-BR03,P-BR04,P-BR05,P-BR06" "radar-bridge-01"
  tick "山区铁路" "P-RW01,P-RW02,P-RW03,P-RW04,P-RW05,P-RW06" "radar-rail-01"
  tick "郊外工厂" "P-FC01,P-FC02,P-FC03,P-FC04,P-FC05,P-FC06" "radar-factory-01"
}

if [ "$ONCE" = 1 ]; then
  round
  exit 0
fi

printf '保活开始：每 %ss 一轮，Ctrl-C 停止（目标 %s）\n' "$INTERVAL" "$BASE"
# 单轮失败不退出：后端重启、网络抖动都会让某一轮失败，而保活的价值恰恰是"持续"
while true; do
  round || printf '  本轮有失败（后端未就绪？），下一轮继续\n'
  sleep "$INTERVAL"
done
