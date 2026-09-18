#!/bin/bash
# 给四个演示场景灌模拟数据：每个场景先补一段历史（曲线用），再补一段实时（当前值用）。
#
# 用法：
#   tools/radar_simulator/seed_all_scenes.sh                      # 打 http://localhost:8080
#   BASE=http://localhost:18083/api/v1 tools/radar_simulator/seed_all_scenes.sh
#
# 设计取舍：
#   · 历史段用 BACKFILL（`--backdate-days 1` 会自动选模式）——补报不改在线状态、不触发告警，
#     正是曲线要的东西；
#   · 实时段不带 backdate，最后一轮落在"现在"，`latest` 才有值可显示（latest 只认 REALTIME）；
#   · 每轮间隔压到 0.05s，纯粹为了播种快；真实设备当然不是这个节奏。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
BASE="${BASE:-http://localhost:8080/api/v1}"
URL="$BASE/ingest/measurements"
KEY="${INGEST_KEY:-dev-ingest-key}"
HISTORY_COUNT="${HISTORY_COUNT:-24}"    # 24 × 30min = 12 小时历史
REALTIME_COUNT="${REALTIME_COUNT:-6}"
SIM="$ROOT/tools/radar_simulator/radar_simulator.py"

seed() {
  local title="$1" points="$2" device="$3"
  echo "== $title =="
  if [ -n "$device" ]; then
    extra=(--points "$points" --device "$device")
  else
    extra=(--points "$points")
  fi
  python3 "$SIM" --url "$URL" --key "$KEY" "${extra[@]}" \
      --count "$HISTORY_COUNT" --interval 0.05 --step-minutes 30 --backdate-days 1 \
      --seed 20260918 2>&1 | tail -1
  python3 "$SIM" --url "$URL" --key "$KEY" "${extra[@]}" \
      --count "$REALTIME_COUNT" --interval 0.05 --step-minutes 30 --ingest-mode REALTIME \
      --seed 20260918 2>&1 | tail -1
}

seed "山地边坡（radar-001 + radar-002）" \
     "P-HK01,P-HK02,P-HK03,P-BP01,P-BP02,P-BP03,P-BP04" ""
seed "野外桥梁（radar-bridge-01）" \
     "P-BR01,P-BR02,P-BR03,P-BR04,P-BR05,P-BR06" "radar-bridge-01"
seed "山区铁路（radar-rail-01）" \
     "P-RW01,P-RW02,P-RW03,P-RW04,P-RW05,P-RW06" "radar-rail-01"
seed "郊外工厂（radar-factory-01）" \
     "P-FC01,P-FC02,P-FC03,P-FC04,P-FC05,P-FC06" "radar-factory-01"

echo "完成。打开大屏后用顶栏「项目」下拉切换场景；曲线在测点详情页（每点两个测项）。"
