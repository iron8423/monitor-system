<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import * as api from '@/api/monitor'
import AlarmQueue from '@/components/AlarmQueue.vue'
import MediaGallery from '@/components/MediaGallery.vue'
import MediaUploader from '@/components/MediaUploader.vue'
import SeriesChart from '@/components/SeriesChart.vue'
import { useThresholds } from '@/composables/useThresholds'

/**
 * 测点详情（阶段 2 + 阶段 5 的影像）。验收第 6 条的原文是
 * 「**点一个监测点**：最新值、历史曲线、照片/影像、告警全出来」，
 * 所以这里是那一条的主战场——四样东西各占一个页签，点开就在同一个点上。
 *
 * 页签是**懒加载**的：影像与告警只在第一次切过去时才请求（`loaded` 标记）。
 * 全部预取的话，每次点测点都会多发两个请求，而绝大多数时候用户只看曲线。
 *
 * 表头的「共 N 点」跟着曲线走，数据表用的就是同一份数据（`chartPoints`），
 * 不另发请求——同一批点画一次图再列一次表，没有任何理由拉两遍。
 */

defineOptions({ name: 'PointsView' })

const router = useRouter()

const points = ref([])
const selectedId = ref(null)
const latest = ref(null)
const series = ref(null)

const metricCode = ref('defo_mm')
const granularity = ref('raw')
const rangeHours = ref(24)
const loading = ref(false)

const activeTab = ref('chart')

// 模板 ref：上传成功后让画廊重拉。`<script setup>` 里没有 `$refs`，
// 必须声明同名 ref 变量——写 `$refs.gallery` 不会报错，只是永远 undefined，上传完列表不刷新。
const gallery = ref(null)

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

const selected = computed(() => points.value.find((p) => p.id === selectedId.value) || null)

/**
 * 阈值线取自 `/alarm-rules`（管理员在管理端「告警规则」页签里改），不再写死。
 * 过滤口径与「拉不到就不画」的理由都在 {@link useThresholds} 里。
 */
const { thresholds, failed: thresholdsFailed } = useThresholds(metricCode, selectedId)

const chartPoints = computed(() => series.value?.points || [])

/** 最新值面板：把 latest 那袋杂七杂八的字段整理成可展示的行 */
const latestRows = computed(() => {
  const l = latest.value?.latest
  if (!l) return []
  return Object.entries(l)
    .filter(([, v]) => v !== null && v !== undefined && typeof v !== 'object')
    .map(([k, v]) => ({ key: k, value: v }))
})

/** 数据表：单位跟着当前测项走，表头里带出来，免得看数字不知道量纲 */
const tableRows = computed(() =>
  chartPoints.value.map((p, i) => ({ idx: i + 1, t: p.t, v: p.v })),
)

// ---- 资料页签的归属链：测点 → 对象 → 场景 ----
const objects = ref([])
const scenes = ref([])

const chain = computed(() => {
  const p = selected.value
  if (!p) return null
  const obj = objects.value.find((o) => o.id === p.objectId) || null
  const scene = obj ? scenes.value.find((s) => s.id === obj.sceneId) || null : null
  return { object: obj, scene }
})

// ---- 懒加载：影像 / 告警 ----
const loadedTabs = ref({})
const pointAlarms = ref([])
const alarmsLoading = ref(false)

async function loadAlarms() {
  if (!selectedId.value) return
  alarmsLoading.value = true
  try {
    const page = await api.listAlarms({ pointId: selectedId.value, pageNum: 1, pageSize: 50 })
    pointAlarms.value = page?.records || []
  } catch {
    pointAlarms.value = []
  } finally {
    alarmsLoading.value = false
  }
}

function onTabChange(name) {
  if (loadedTabs.value[name]) return
  loadedTabs.value[name] = true
  if (name === 'alarms') loadAlarms()
}

/** 告警行点进去看完整详情与处置链：复用告警中心的 ?id= 深链，不在这里再实现一遍抽屉 */
function openAlarm(row) {
  router.push({ path: '/alarms', query: { id: row.id } })
}

async function loadPoints() {
  try {
    points.value = (await api.listPoints()) || []
    if (points.value.length && !selectedId.value) selectedId.value = points.value[0].id
  } catch {
    points.value = []
  }
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

async function loadChain() {
  try {
    const [objs, scns] = await Promise.all([api.listObjects(), api.listScenes()])
    objects.value = objs || []
    scenes.value = scns || []
  } catch {
    objects.value = []
    scenes.value = []
  }
}

onMounted(() => {
  loadPoints()
  loadChain()
})

// 换测点时，已经打开过的影像/告警页签必须重新拉——否则会出现
// 「切到 B 测点，页签里还是 A 的照片和警情」这种最容易在演示现场被问住的错
watch(selectedId, () => {
  if (loadedTabs.value.alarms) loadAlarms()
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
        <el-tabs v-model="activeTab" class="detail-tabs" @tab-change="onTabChange">
          <!-- ① 资料：档案字段 + 归属链 -->
          <el-tab-pane label="资料" name="profile">
            <div v-if="selected" class="profile">
              <div v-for="row in [
                { k: '点号', v: selected.code, mono: true },
                { k: '名称', v: selected.name },
                { k: '类型', v: selected.type },
                { k: '经度', v: selected.longitude, mono: true },
                { k: '纬度', v: selected.latitude, mono: true },
                { k: '高程', v: selected.altitude, mono: true },
                { k: '启用', v: selected.enabled ? '是' : '否' },
                { k: '所属对象', v: chain?.object?.name },
                { k: '所属场景', v: chain?.scene?.name },
                { k: '建档时间', v: selected.createdAt, mono: true },
              ]" :key="row.k" class="field">
                <span class="mk-muted field-k">{{ row.k }}</span>
                <span :class="{ 'mk-mono': row.mono, empty: row.v === null || row.v === undefined }">
                  {{ row.v ?? '—' }}
                </span>
              </div>
            </div>
            <el-empty v-else description="请选择测点" :image-size="60" />
          </el-tab-pane>

          <!-- ② 曲线：工具栏 + 图 + 最新值 -->
          <el-tab-pane label="曲线" name="chart">
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
              <el-tooltip
                v-if="thresholdsFailed"
                content="拉取 /alarm-rules 失败，本图未画阈值线——宁可没有线，也不画错的线"
              >
                <span class="mk-muted th-warn">阈值线未加载</span>
              </el-tooltip>
              <span class="mk-muted count">共 {{ chartPoints.length }} 点</span>
              <el-button size="small" link type="primary" @click="loadDetail">刷新</el-button>
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

            <div class="sub-title">最新值</div>
            <div class="latest-body">
              <template v-if="latestRows.length">
                <div v-for="r in latestRows" :key="r.key" class="latest-row">
                  <span class="mk-muted">{{ r.key }}</span>
                  <span class="mk-mono">{{ r.value }}</span>
                </div>
              </template>
              <el-empty v-else description="该测点暂无数据" :image-size="60" />
            </div>
          </el-tab-pane>

          <!-- ③ 数据表：与曲线同一份数据 -->
          <el-tab-pane label="数据表" name="table">
            <div class="table-note mk-muted">
              与「曲线」页签同一份数据（当前条件：{{ currentMetric?.label }} ·
              {{ RANGES.find((r) => r.value === rangeHours)?.label }}），可排序、可复制。
            </div>
            <el-table :data="tableRows" v-loading="loading" size="small" max-height="420" stripe>
              <el-table-column prop="idx" label="#" width="70" />
              <el-table-column prop="t" label="采集时间（带时区）" min-width="220" sortable />
              <el-table-column prop="v" :label="`测值（${series?.unit || currentMetric?.unit || ''}）`" min-width="140" sortable />
              <template #empty>
                <el-empty description="当前条件下没有数据" :image-size="50" />
              </template>
            </el-table>
          </el-tab-pane>

          <!-- ④ 影像：验收第 6 条的「照片/影像」 -->
          <el-tab-pane label="影像" name="media">
            <div class="toolbar">
              <MediaUploader :point-id="selectedId" @uploaded="() => gallery?.reload()" />
              <span class="mk-spacer" />
              <span class="mk-muted count">点缩略图看大图，可左右翻页</span>
            </div>
            <!-- deletable：详情页是管理影像的场合（看 + 传 + 撤），与 /media 总览页一致；
                 3D 大屏浮窗**不给**这个入口——那里是「看」的场合 -->
            <MediaGallery ref="gallery" :point-id="selectedId" deletable />
          </el-tab-pane>

          <!-- ⑤ 告警：该测点的全部警情（不只未解除） -->
          <el-tab-pane label="告警" name="alarms">
            <div class="table-note mk-muted">
              该测点的全部警情（含已解除/误报）。点一行到告警中心看时间线与处置。
            </div>
            <AlarmQueue
              :alarms="pointAlarms"
              :loading="alarmsLoading"
              show-status
              empty-text="该测点没有警情"
              @row-click="openAlarm"
            />
          </el-tab-pane>
        </el-tabs>
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

/* 页签头往面板里缩进一点，与下面的工具栏对齐（面板本身没有内边距） */
.detail-tabs :deep(.el-tabs__header) {
  margin: 0;
  padding: 0 16px;
}

.detail-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
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

.th-warn {
  font-size: 12px;
  cursor: help;
}

.chart-box {
  padding: 8px;
}

.sub-title {
  padding: 4px 16px 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--mk-text);
}

.latest-body {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 24px;
  padding: 10px 16px 16px;
}

.latest-row {
  display: flex;
  gap: 10px;
  font-size: 13px;
}

.profile {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 10px 24px;
  padding: 16px;
}

.field {
  display: flex;
  gap: 10px;
  font-size: 13px;
}

.field-k {
  min-width: 68px;
}

.empty {
  color: var(--mk-text-sub);
}

/* 表格与说明之间留白；表格自带边框，不再套面板 */
.table-note {
  padding: 12px 16px;
  font-size: 12px;
}
</style>
