<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import * as api from '@/api/monitor'
import AlarmQueue from '@/components/AlarmQueue.vue'
import MediaGallery from '@/components/MediaGallery.vue'
import MediaUploader from '@/components/MediaUploader.vue'
import SeriesChart from '@/components/SeriesChart.vue'
import { useThresholds } from '@/composables/useThresholds'
import { formatTime, fromNow } from '@/utils/format'
import { createRequestGuard } from '@/utils/requestGuard'
import {
  MAX_RAW_POINTS,
  RAW_SAFE_WINDOW_HOURS,
  autoGranularity,
  rawFitsInLimit,
} from '@/utils/seriesGranularity'
import { suggestRange } from '@/utils/timeline'

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

/**
 * 测项选项来自**档案**（GET /metrics，每点一份 `{pointId, code, name, unit, sortOrder}`），
 * 不再写死 defo_mm / rate_mm_d —— 加一种测项只需管理端加一行数据，这里零改动。
 * 优先用「该测点自己的测项」；档案里没有该点就退回全部测项。
 */
const metrics = ref([])
const metricOptions = computed(() => {
  const mine = metrics.value.filter((m) => m.pointId === selectedId.value)
  const use = mine.length ? mine : metrics.value
  const seen = new Map()
  for (const m of use) if (m?.code && !seen.has(m.code)) seen.set(m.code, m)
  return [...seen.values()]
    .sort((a, b) => (a.sortOrder ?? 99) - (b.sortOrder ?? 99))
    .map((m) => ({ value: m.code, label: m.name || m.code, unit: m.unit || '' }))
})

const GRANULARITIES = [
  { value: 'raw', label: '原始', needsRawBudget: true },
  { value: 'hour', label: '按小时' },
  { value: 'day', label: '按天' },
]

/**
 * 「原始」这一档能不能选，取决于窗口装不装得进后端的 5000 点上限（P0-3）。
 *
 * 为什么是**禁用**而不是"选了再自动降级"：降级会让选择器显示「原始」而图上画的是
 * 聚合点——用户拿它当原始曲线去判突跳，那就成了错误结论的来源。宁可当场说清
 * 「原始粒度只支持 6 小时以内」，也不要给出一个名不副实的图。
 */
const rawDisabled = computed(() => !rawFitsInLimit(rangeHours.value))
const rawDisabledHint = computed(
  () => `原始点单次最多 ${MAX_RAW_POINTS} 点（生产 5 秒采样约 ${RAW_SAFE_WINDOW_HOURS} 小时）：`
    + `请把窗口缩到 ${RAW_SAFE_WINDOW_HOURS} 小时以内，或改用按小时/按天。`,
)

/** 窗口变长到装不下 raw 时，把已经选中的「原始」降到「按小时」——只在用户的选择**过细**时降。 */
const GRAN_RANK = { raw: 0, hour: 1, day: 2 }
watch(rangeHours, (hours) => {
  const auto = autoGranularity(hours)
  if (GRAN_RANK[granularity.value] < GRAN_RANK[auto]) granularity.value = auto
}, { immediate: true })

const RANGES = [
  { value: 1, label: '近 1 小时' },
  { value: 24, label: '近 24 小时' },
  { value: 24 * 7, label: '近 7 天' },
  { value: 24 * 30, label: '近 30 天' },
]

const currentMetric = computed(
  () => metricOptions.value.find((m) => m.value === metricCode.value) || metricOptions.value[0] || null,
)

const selected = computed(() => points.value.find((p) => p.id === selectedId.value) || null)

/**
 * 阈值线取自 `/alarm-rules`（管理员在管理端「告警规则」页签里改），不再写死。
 * 过滤口径与「拉不到就不画」的理由都在 {@link useThresholds} 里。
 */
const { thresholds, failed: thresholdsFailed } = useThresholds(metricCode, selectedId)

const chartPoints = computed(() => series.value?.points || [])

/**
 * 曲线为空时的「为什么」与「怎么办」。
 *
 * 起因是一次真实的困惑（2026-09-17 实测）：演示库最新一行数据停在两天前，
 * 而本页默认取「近 24 小时」——曲线空着，界面只写「当前条件下没有数据」，
 * 看的人分不清「这个点从来没上报过」和「上报过，但比窗口旧」。
 *
 * 判据用 `/points/{id}/latest` 的 `collectTime`：**它不受窗口限制**（取的是该点最新一行），
 * 正好能回答「最后一条数据在哪」。三种空态分开说，并给出能覆盖它的最小窗口。
 * 刻意**不自动**改窗口：用户选的档位就是用户选的，替他改只会让「共 N 点」变得无法解释。
 */
const latestTime = computed(() => latest.value?.latest?.collectTime || null)

const currentRangeLabel = computed(
  () => RANGES.find((r) => r.value === rangeHours.value)?.label || `近 ${rangeHours.value} 小时`,
)

const emptyHint = computed(() => {
  if (loading.value || chartPoints.value.length) return null
  // 没选测点（或点列表还没回来）时什么都不说：那不是「这个点没有数据」，而是「还没轮到它」。
  // 2026-09-17 用无头浏览器实测撞到过：空态写着「该测点还没有收到任何数据」，
  // 而屏幕上压根没有选中的测点——一句话把「没选」说成了「没数据」。
  if (!selectedId.value) return null
  const t = latestTime.value
  if (!t) {
    return {
      kind: 'no-data',
      title: `${selected.value?.code || '该测点'} 还没有收到任何数据`,
      detail: '不是窗口的问题——档案在、数据一条都没有，先看设备是否上报。',
      suggest: null,
    }
  }
  const ts = Date.parse(t)
  const outOfWindow = Number.isFinite(ts) && Date.now() - ts > rangeHours.value * 3600 * 1000
  if (outOfWindow) {
    const r = suggestRange(t, RANGES)
    const canCover = r && r.value !== rangeHours.value
    return {
      kind: 'stale',
      title: `最近一条数据在 ${formatTime(t)}（${fromNow(t)}），已超出当前「${currentRangeLabel.value}」窗口`,
      detail: canCover
        ? '数据没有丢，只是比窗口旧。'
        : '已经放到最大窗口也覆盖不到它——先确认设备是否还在上报。',
      suggest: canCover ? r : null,
    }
  }
  const lm = latest.value?.latest || {}
  const hasMetric = lm[metricCode.value] !== undefined && lm[metricCode.value] !== null
  if (!hasMetric) {
    return {
      kind: 'no-metric',
      title: `该测点在当前窗口内没有「${currentMetric.value?.label || metricCode.value}」的数据`,
      detail: '该点有数据，但没有这个测项——换一个测项，或把窗口放大。',
      suggest: null,
    }
  }
  return {
    kind: 'empty',
    title: '当前条件下没有数据',
    detail: '该测点在窗口内没有落在筛选条件里的采样点。',
    suggest: null,
  }
})

/**
 * 应用建议窗口，并顺手把粒度切到与后端口径一致的那一档。
 *
 * 判据不是"感觉窗口大了"而是**后端的硬上限**（P0-3）：`raw` 单次最多 5000 点，
 * 生产 5 秒采样下约 6 小时就是上限，超了直接 400。原来的阈值写在 7 天，
 * 那是"接口会不会被拖垮"的预算——两者差着两个数量级，按它自动切换等于没切。
 * 用户仍可手动改回「原始」，但那时窗口超过 6 小时就会被后端 400（这是刻意的：
 * 宁可明确报错，也不要静默截断）。
 */
function applySuggestedRange() {
  const r = emptyHint.value?.suggest
  if (!r) return
  rangeHours.value = r.value
  granularity.value = autoGranularity(r.value)
}

/** latest 里这些不是测项，是元信息（契约 §3）——分开显示，别混进测项行 */
const META_LABELS = {
  collectTime: '采集时间',
  receiveTime: '接收时间',
  quality: '数据质量',
  signal: '信号强度',
}

/** 测项行：名称/单位都来自档案，界面不再自己拼「累计形变(mm)」 */
const metricRows = computed(() => {
  const l = latest.value?.latest
  if (!l) return []
  const rows = metricOptions.value
    .filter((o) => l[o.value] !== undefined && l[o.value] !== null)
    .map((o) => ({ code: o.value, name: o.label, unit: o.unit, value: l[o.value] }))
  // 档案里没有、但设备已经报上来的测项也别丢（"明明有数据却不显示"最费解）
  for (const [k, v] of Object.entries(l)) {
    if (META_LABELS[k] || k === 'position' || rows.some((r) => r.code === k)) continue
    if (typeof v === 'number') rows.push({ code: k, name: k, unit: '', value: v })
  }
  return rows
})

const metaRows = computed(() => {
  const l = latest.value?.latest
  if (!l) return []
  return Object.entries(META_LABELS)
    .filter(([k]) => l[k] !== null && l[k] !== undefined)
    .map(([k, label]) => ({ key: k, label, value: l[k] }))
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

/**
 * 取数守卫（清单第 17 条：快速切换测点时，迟到的旧响应不许写回）。
 *
 * **告警与曲线各一个**：这是两次互不相干的取数，共用一个守卫会互相顶掉——
 * 切到告警页签再点下一个测点，第二次 `loadAlarms` 会让曲线那次 `loadDetail` 的
 * 代号失效，曲线的响应就被丢弃了。守卫的粒度必须与「一次独立取数」对齐。
 */
const alarmsGuard = createRequestGuard()

async function loadAlarms() {
  // 参数**快照**：`await` 之后 `selectedId.value` 可能已经换人了
  const pointId = selectedId.value
  if (!pointId) return
  const token = alarmsGuard.next()
  alarmsLoading.value = true
  try {
    const page = await api.listAlarms({ pointId, pageNum: 1, pageSize: 50 })
    if (!alarmsGuard.isCurrent(token)) return // 迟到了：这是上一个点的警情，不许落到新点名下
    pointAlarms.value = page?.records || []
  } catch {
    // 旧请求的失败同样不许把新测点的警情清空
    if (!alarmsGuard.isCurrent(token)) return
    pointAlarms.value = []
  } finally {
    // 旧请求的 finally 提前关掉**在途**新请求的 loading，用户看到的是「加载完了但什么也没有」
    if (alarmsGuard.isCurrent(token)) alarmsLoading.value = false
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

/** 测项档案：拉不到就退回「无选项」，页面照常显示曲线（不因为档案失败而白屏） */
async function loadMetrics() {
  try {
    metrics.value = (await api.listMetrics()) || []
  } catch {
    metrics.value = []
  }
  syncMetricCode()
}

/** 当前测项在该点没有档案时，切到该点第一个可用测项（否则曲线永远是空的） */
function syncMetricCode() {
  const options = metricOptions.value
  if (!options.length) return
  if (!options.some((o) => o.value === metricCode.value)) {
    metricCode.value = options[0].value
  }
}

const detailGuard = createRequestGuard()

async function loadDetail() {
  // 四项参数全部**快照**：`await` 之后这些 ref 都可能已经变了，
  // 那时「请求」与「它属于哪一代」就对不上——这是「串数据」的另一半，
  // 只挡写回不挡参数，仍会出现「B 点的图配 A 点的标题」
  const pointId = selectedId.value
  if (!pointId) return
  const code = metricCode.value
  // 粒度与窗口的合法性由上面的 watch + 单选按钮禁用保证（装不下 raw 时它根本选不中），
  // 这里直接取用户看到的那一档——不要再偷偷改，图上画的必须是选择器里写的那一档。
  const gran = granularity.value
  const from = new Date(Date.now() - rangeHours.value * 3600 * 1000).toISOString()

  const token = detailGuard.next()
  loading.value = true
  try {
    const [l, s] = await Promise.all([
      api.pointLatest(pointId).catch(() => null),
      api.pointSeries(pointId, { metricCode: code, granularity: gran, from }),
    ])
    if (!detailGuard.isCurrent(token)) return // 迟到的旧响应：一个字都不许写
    latest.value = l
    series.value = s
  } catch {
    if (!detailGuard.isCurrent(token)) return // 旧请求的失败不许把新测点的数据清空
    latest.value = null
    series.value = null
  } finally {
    if (detailGuard.isCurrent(token)) loading.value = false
  }
}

/**
 * 换「选择键」（点 / 测项 / 粒度 / 窗口）时先清空上一个点的结果。
 *
 * 不清的话，从发请求到新响应落地之间：标题、点号已经换成 B，表格和图还是 A 的
 * ——这正是第 17 条在界面上最容易被看见的样子。
 *
 * **手动刷新（模板里的「刷新」按钮直接调 `loadDetail`）不走这里**：同参重试没有
 * 「上一个点」，清了只会让图白闪一下。两者因此分成两个入口，而不是给 `loadDetail`
 * 加参数——`watch` 的回调与 `@click` 都会把事件对象/新旧值当第一个实参塞进来。
 */
function reloadForSelection() {
  latest.value = null
  series.value = null
  loadDetail()
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
  loadMetrics()
})

onBeforeUnmount(() => {
  // 卸载后到达的响应没有组件可写了，但它仍会写进 ref 并可能触发 Vue 警告
  detailGuard.invalidate()
  alarmsGuard.invalidate()
})

watch(selectedId, syncMetricCode)

// 换测点时，已经打开过的影像/告警页签必须重新拉——否则会出现
// 「切到 B 测点，页签里还是 A 的照片和警情」这种最容易在演示现场被问住的错
watch(selectedId, () => {
  if (!loadedTabs.value.alarms) return
  pointAlarms.value = [] // 切点瞬间先清：不要让 A 点的警情挂在新点号下面等响应
  loadAlarms()
})

watch([selectedId, metricCode, granularity, rangeHours], reloadForSelection)
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
                // 建档时间也走展示层格式化：后端是带纳秒的 ISO8601，直接贴出来没法读
                { k: '建档时间', v: formatTime(selected.createdAt), mono: true },
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
                <el-radio-button v-for="m in metricOptions" :key="m.value" :value="m.value">
                  {{ m.label }}
                </el-radio-button>
              </el-radio-group>

              <el-radio-group v-model="granularity" size="small">
                <el-radio-button
                  v-for="g in GRANULARITIES"
                  :key="g.value"
                  :value="g.value"
                  :disabled="g.needsRawBudget === true && rawDisabled"
                  :title="g.needsRawBudget === true && rawDisabled ? rawDisabledHint : ''"
                >
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

            <el-alert
              v-if="emptyHint"
              type="info"
              :closable="false"
              show-icon
              class="empty-hint"
              :title="emptyHint.title"
            >
              <div class="hint-body">
                <span>{{ emptyHint.detail }}</span>
                <el-button
                  v-if="emptyHint.suggest"
                  size="small"
                  type="primary"
                  link
                  @click="applySuggestedRange"
                >
                  切到{{ emptyHint.suggest.label }}
                </el-button>
              </div>
            </el-alert>

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
              <template v-if="metricRows.length || metaRows.length">
                <div v-for="r in metricRows" :key="r.code" class="latest-row">
                  <span class="mk-muted">{{ r.name }}</span>
                  <span class="mk-mono">{{ r.value }} {{ r.unit }}</span>
                </div>
                <div v-for="r in metaRows" :key="r.key" class="latest-row meta">
                  <span class="mk-muted">{{ r.label }}</span>
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
                <el-empty :description="emptyHint?.title || '当前条件下没有数据'" :image-size="50">
                  <el-button
                    v-if="emptyHint?.suggest"
                    size="small"
                    type="primary"
                    @click="applySuggestedRange"
                  >
                    切到{{ emptyHint.suggest.label }}
                  </el-button>
                </el-empty>
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

/* 空态提示条：说明「为什么空」并给一键切窗口，紧贴在图上方 */
.empty-hint {
  margin: 10px 16px 0;
}

.hint-body {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  font-size: 12px;
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

/* 元信息（采集时间/质量/信号）压暗一档，别和测项值抢注意力 */
.latest-row.meta {
  opacity: 0.72;
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
