<script setup>
import { computed, onMounted, ref, watch } from 'vue'

import * as api from '@/api/monitor'
import SeriesChart from '@/components/SeriesChart.vue'
import { buildThresholdLines, describeRules } from '@/utils/thresholds'

/**
 * 测点与曲线（阶段 2）。对应里程碑 M3 的判据：**前端曲线拉到真实数据**。
 *
 * 曲线只在选了测点之后拉；`/points/{id}/series` 的 `from` 由时间范围算，
 * `to` 不传（后端默认取到「现在」）。
 */

defineOptions({ name: 'PointsView' })

const points = ref([])
const selectedId = ref(null)
const latest = ref(null)
const series = ref(null)

const metricCode = ref('defo_mm')
const granularity = ref('raw')
const rangeHours = ref(24)
const loading = ref(false)

const METRICS = [
  { value: 'defo_mm', label: '累计形变', unit: 'mm' },
  { value: 'rate_mm_d', label: '形变速率', unit: 'mm/d' },
]

const GRANULARITIES = [
  { value: 'raw', label: '原始' },
  { value: 'hour', label: '按小时' },
  { value: 'day', label: '按天' },
]

const RANGES = [
  { value: 1, label: '近 1 小时' },
  { value: 24, label: '近 24 小时' },
  { value: 24 * 7, label: '近 7 天' },
  { value: 24 * 30, label: '近 30 天' },
]

const currentMetric = computed(() => METRICS.find((m) => m.value === metricCode.value))

/**
 * 阈值线来自 `/alarm-rules`（原来写死 ±3mm）。
 *
 * 换算规则见 `utils/thresholds.js`：只取 THRESHOLD 类型、测项必须精确匹配、
 * 全局规则对所有测点生效而测点规则只对该点生效。管理端改了规则，这里刷新一下
 * 就跟着变——不会再出现「规则改了、图上的线还是老值」。
 */
const rules = ref([])
const thresholds = computed(() =>
  buildThresholdLines(rules.value, { pointId: selectedId.value, metricCode: metricCode.value }),
)
const ruleSummary = computed(() =>
  describeRules(rules.value, { pointId: selectedId.value, metricCode: metricCode.value }),
)

const chartPoints = computed(() => series.value?.points || [])

/** 最新值面板：把 latest 那袋杂七杂八的字段整理成可展示的行 */
const latestRows = computed(() => {
  const l = latest.value?.latest
  if (!l) return []
  return Object.entries(l)
    .filter(([, v]) => v !== null && v !== undefined && typeof v !== 'object')
    .map(([k, v]) => ({ key: k, value: v }))
})

async function loadPoints() {
  try {
    points.value = (await api.listPoints()) || []
    if (points.value.length && !selectedId.value) selectedId.value = points.value[0].id
  } catch {
    points.value = []
  }
}

/** 规则不是「首屏必需」：拉失败不该毁掉曲线，空数组就是没有阈值线 */
async function loadRules() {
  try {
    rules.value = (await api.listAlarmRules()) || []
  } catch {
    rules.value = []
  }
}

/** 「刷新」一次把曲线与规则都拉一遍（规则可能在管理端刚被改过） */
async function refreshAll() {
  await Promise.all([loadRules(), loadDetail()])
}

async function loadDetail() {
  if (!selectedId.value) return
  const from = new Date(Date.now() - rangeHours.value * 3600 * 1000).toISOString()
  loading.value = true
  try {
    const [l, s] = await Promise.all([
      api.pointLatest(selectedId.value).catch(() => null),
      api.pointSeries(selectedId.value, {
        metricCode: metricCode.value,
        granularity: granularity.value,
        from,
      }),
    ])
    latest.value = l
    series.value = s
  } catch {
    latest.value = null
    series.value = null
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await loadPoints()
  await loadRules()
})

watch([selectedId, metricCode, granularity, rangeHours], loadDetail)
</script>

<template>
  <div class="points-page">
    <div class="mk-panel point-panel">
      <div class="mk-panel-title">
        测点
        <span class="mk-muted title-sub">{{ points.length }}</span>
      </div>
      <div class="point-list">
        <div
          v-for="p in points"
          :key="p.id"
          class="point-item"
          :class="{ active: p.id === selectedId }"
          @click="selectedId = p.id"
        >
          <span class="mk-mono point-code">{{ p.code }}</span>
          <span class="point-name">{{ p.name }}</span>
        </div>
        <el-empty v-if="!points.length" description="没有测点" :image-size="60" />
      </div>
    </div>

    <div class="right-col">
      <div class="mk-panel">
        <div class="mk-panel-title">
          形变曲线
          <span v-if="series" class="mk-muted mk-mono title-sub">
            {{ series.pointCode }} · {{ series.metricCode }} · {{ series.unit }}
          </span>
          <span class="mk-spacer" />
          <el-button size="small" link type="primary" @click="refreshAll">刷新</el-button>
        </div>

        <div class="toolbar">
          <el-radio-group v-model="metricCode" size="small">
            <el-radio-button v-for="m in METRICS" :key="m.value" :value="m.value">
              {{ m.label }}
            </el-radio-button>
          </el-radio-group>

          <el-radio-group v-model="granularity" size="small">
            <el-radio-button v-for="g in GRANULARITIES" :key="g.value" :value="g.value">
              {{ g.label }}
            </el-radio-button>
          </el-radio-group>

          <el-select v-model="rangeHours" size="small" style="width: 120px">
            <el-option v-for="r in RANGES" :key="r.value" :label="r.label" :value="r.value" />
          </el-select>

          <span class="mk-spacer" />
          <span class="mk-muted count">共 {{ chartPoints.length }} 点</span>
          <!-- 阈值线的出处：写死的时候没人会问，接了规则就得说清楚它从哪来 -->
          <span class="mk-muted count" :class="ruleSummary.tone === 'warn' ? 'mk-warn' : 'mk-ok'">
            {{ ruleSummary.text }}
          </span>
        </div>

        <div class="chart-box">
          <SeriesChart
            :points="chartPoints"
            :unit="series?.unit || currentMetric?.unit || ''"
            :metric-label="currentMetric?.label || ''"
            :thresholds="thresholds"
            :loading="loading"
            height="320px"
          />
        </div>
      </div>

      <div class="mk-panel">
        <div class="mk-panel-title">
          最新值
          <span class="mk-spacer" />
          <!-- 影像挂点（阶段 5）：从这里跳到该测点的现场照片 -->
          <router-link v-if="selectedId" :to="`/media?pointId=${selectedId}`">
            <el-button size="small" link type="primary">现场照片</el-button>
          </router-link>
        </div>
        <div class="latest-body">
          <template v-if="latestRows.length">
            <div v-for="r in latestRows" :key="r.key" class="latest-row">
              <span class="mk-muted">{{ r.key }}</span>
              <span class="mk-mono">{{ r.value }}</span>
            </div>
          </template>
          <el-empty v-else description="该测点暂无数据" :image-size="60" />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.points-page {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.point-panel {
  width: 220px;
  flex-shrink: 0;
}

.point-list {
  padding: 6px;
}

.point-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  border-radius: 4px;
  cursor: pointer;
  transition: background 0.15s;
}

.point-item:hover {
  background: var(--mk-bg);
}

.point-item.active {
  background: rgba(31, 111, 235, 0.08);
}

.point-item.active .point-code {
  color: var(--mk-primary);
}

.point-code {
  font-size: 13px;
}

.point-name {
  font-size: 12px;
  color: var(--mk-text-sub);
}

.right-col {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.title-sub {
  margin-left: 10px;
  font-size: 12px;
  font-weight: 400;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 10px 16px;
  border-bottom: 1px solid var(--mk-border);
}

.count {
  font-size: 12px;
}

.chart-box {
  padding: 8px;
}

.latest-body {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 24px;
  padding: 14px 16px;
}

.latest-row {
  display: flex;
  gap: 10px;
  font-size: 13px;
}
</style>
