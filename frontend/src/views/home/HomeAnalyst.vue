<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'

import * as api from '@/api/monitor'
import AlarmQueue from '@/components/AlarmQueue.vue'
import SeriesChart from '@/components/SeriesChart.vue'
import StatTiles from '@/components/StatTiles.vue'
import { usePolling } from '@/composables/usePolling'
import { useThresholds } from '@/composables/useThresholds'

/**
 * 研判工作台（研判员）。需求 §3 给研判员的定位是「看曲线/照片/现场，判断真假」——
 * 所以这一页和其他三个不同：**不只是一张待办表，旁边必须能看图**。
 * 队列里点一条，右边就出那条警情所在测点的形变曲线，这才是判断的证据。
 *
 * 队列取 `status=CONFIRMED`：值班员 confirm 之后落到 CONFIRMED，那正是等研判的状态。
 * 研判员research → OBSERVING（研判中）。
 *
 * 需求里还提到「照片/现场」，那是影像挂点（阶段 5，`/media`），后端有上传与读取接口
 * 但前端还没有页面，本页因此只能给曲线——已在脚注里写明。
 *
 * 「看曲线判真假」还有个前提：曲线本身要可信。所以这里只画 `defo_mm` 原始点
 * （`granularity=raw`），不做任何聚合——聚合会磨平瞬时突跳，而突跳正是判断传感器
 * 异常还是真实形变的关键。
 */

defineOptions({ name: 'HomeAnalyst' })

const router = useRouter()

const summary = ref(null)
const queue = ref([])
const confirmedTotal = ref(null)
const observingTotal = ref(null)
const falseAlarmTotal = ref(null)
const loading = ref(true)
const errorMsg = ref('')

const selected = ref(null)
const series = ref(null)
const seriesLoading = ref(false)

/**
 * 阈值线与 `PointsView` 同一份实现（{@link useThresholds}）。
 * 这里只研判 defo_mm 的原始点位，所以测项固定；测点跟着选中那条警情走。
 */
const { thresholds } = useThresholds('defo_mm', computed(() => selected.value?.pointId))

async function pick(row) {
  selected.value = row
  series.value = null
  // 设备告警没有 pointId，画不了曲线；这里不报错，让右栏的空态去解释
  if (!row?.pointId) return

  seriesLoading.value = true
  try {
    series.value = await api.pointSeries(row.pointId, {
      metricCode: 'defo_mm',
      granularity: 'raw',
    })
  } catch {
    series.value = null
  } finally {
    seriesLoading.value = false
  }
}

async function load() {
  loading.value = true
  errorMsg.value = ''
  try {
    const projects = await api.listProjects().catch(() => [])
    const pid = projects?.[0]?.id

    const [sum, confirmed, observing, misreported] = await Promise.all([
      pid ? api.projectSummary(pid).catch(() => null) : null,
      api.listAlarms({ status: 'CONFIRMED', pageNum: 1, pageSize: 8 }).catch(() => null),
      api.listAlarms({ status: 'OBSERVING', pageNum: 1, pageSize: 1 }).catch(() => null),
      api.listAlarms({ status: 'FALSE_ALARM', pageNum: 1, pageSize: 1 }).catch(() => null),
    ])
    summary.value = sum
    queue.value = confirmed?.records || []
    confirmedTotal.value = confirmed?.total ?? null
    observingTotal.value = observing?.total ?? null
    falseAlarmTotal.value = misreported?.total ?? null

    // 首次进页面自动选第一条，右栏不至于空着。
    // 已有选中时不覆盖——轮询每 15s 跑一次，覆盖会把用户正在看的那条刷掉。
    if (!selected.value && queue.value.length) {
      await pick(queue.value[0])
    }
  } catch (e) {
    errorMsg.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

// 队列要跟得上（15s），但曲线是历史数据，不随轮询重取——只在切换选中时拉一次。
usePolling(load, 15000)

const kpis = computed(() => [
  {
    key: 'confirmed',
    label: '待研判',
    value: confirmedTotal.value,
    unit: '',
    danger: true,
    tip: '状态为 CONFIRMED 的警情（已由值班员确认，等研判定性）',
  },
  { key: 'observing', label: '研判中', value: observingTotal.value, unit: '' },
  {
    key: 'false',
    label: '已判误报',
    value: falseAlarmTotal.value,
    unit: '',
    tip: '历史累计，不是待办——仅用于看误报量',
  },
  // 含设备告警：设备离线这类只挂 deviceId、不挂测点，本页左侧队列里看不到它们，
  // 所以这个数会比队列条数大（后端 ProjectSummaryService.activeAlarmCount 两条都算）
  { key: 'alerts', label: '未解除警情', value: summary.value?.alertCount, unit: '', danger: true,
    tip: '本项目下所有非终态警情，含设备告警。设备告警不挂测点，故不在左侧队列里' },
])

const chartPoints = computed(() => series.value?.points || [])
</script>

<template>
  <div class="page" v-loading="loading">
    <el-alert
      v-if="errorMsg"
      type="error"
      :closable="false"
      show-icon
      :title="errorMsg"
      class="mb"
    />

    <StatTiles :items="kpis" />

    <div class="cols">
      <div class="mk-panel col">
        <div class="mk-panel-title">
          待研判警情
          <span class="mk-muted title-sub">点一行看该测点曲线</span>
          <span class="mk-spacer" />
          <el-button
            size="small"
            link
            type="primary"
            @click="router.push({ path: '/alarms', query: { status: 'CONFIRMED' } })"
          >
            去告警中心
          </el-button>
        </div>
        <AlarmQueue
          :alarms="queue"
          :loading="loading"
          empty-text="没有待研判的警情"
          @row-click="pick"
        />
      </div>

      <div class="mk-panel col">
        <div class="mk-panel-title">
          形变曲线
          <span v-if="selected" class="mk-muted title-sub">
            <span class="mk-mono">{{ selected.pointCode || selected.deviceCode || '—' }}</span>
            · 原始点位（未聚合）
          </span>
          <span class="mk-spacer" />
          <el-button
            v-if="selected"
            size="small"
            link
            type="primary"
            @click="pick(selected)"
          >
            刷新曲线
          </el-button>
        </div>

        <div class="chart-box">
          <SeriesChart
            v-if="chartPoints.length"
            :points="chartPoints"
            :unit="series?.unit || 'mm'"
            metric-label="形变"
            :thresholds="thresholds"
            :loading="seriesLoading"
            height="320px"
          />
          <el-empty
            v-else
            :image-size="70"
            :description="
              !selected
                ? '从左边选一条警情'
                : !selected.pointId
                  ? '设备告警没有关联测点，画不了曲线'
                  : seriesLoading
                    ? '加载中'
                    : '该测点没有形变数据'
            "
          />
        </div>
      </div>
    </div>

    <div class="mk-panel">
      <div class="mk-footnote">
        队列按状态取：研判员收 <span class="mk-mono">CONFIRMED</span>（已确认、待定性），
        研判动作 <span class="mk-mono">research</span> 之后转入
        <span class="mk-mono">OBSERVING</span>。曲线只画
        <span class="mk-mono">defo_mm</span> 的原始点位并直接显示后端给的原始 ISO 时间——
        不做聚合，聚合会磨平瞬时突跳，而突跳正是分辨「传感器异常」与「真实形变」的关键。
        阈值线取自 <span class="mk-mono">/alarm-rules</span>（与
        <span class="mk-mono">/points</span> 同一份实现
        <span class="mk-mono">composables/useThresholds</span>），
        所以图上线的条数与等级跟着管理员配的规则走，不再是写死的 ±3mm。
        需求里的「照片/现场」属影像挂点（阶段 5），尚未落地，本页暂只能看图。
      </div>
    </div>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.mb {
  margin-bottom: 0;
}

.cols {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.2fr);
  gap: 16px;
}

@media (max-width: 1100px) {
  .cols {
    grid-template-columns: minmax(0, 1fr);
  }
}

.col {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.title-sub {
  margin-left: 10px;
  font-size: 12px;
  font-weight: 400;
}

.chart-box {
  padding: 8px;
}
</style>
