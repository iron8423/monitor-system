<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import {
  Cesium,
  FARFIELD_TERRAIN_ENABLED,
  ION_CONFIGURED,
  LOCAL_SCENE_ENABLED,
  applyViewerTheme,
  createViewer,
  flyToPoint,
  flyToPoints,
  setupImagery,
  setupTerrain,
} from '@/cesium/createViewer'
import { animateLookAt, createDigitalTwinScene, fitSceneView, panLookAt } from '@/cesium/digitalTwinScene'
import { ASSET_OVERRIDE, LIT_RENDERING } from '@/cesium/renderProfile'
import { createPointLayer } from '@/cesium/pointLayer'
import { createLabelOverlay } from '@/cesium/labelOverlay'
import { createHeatmapLayer } from '@/cesium/heatmapLayer'
import { createZoneLayer } from '@/cesium/zoneLayer'
import { deviceCoverage, projectDigitalTwin } from '@/api/monitor'
import MediaGallery from '@/components/MediaGallery.vue'
import AnimatedNumber from '@/components/AnimatedNumber.vue'
import ThemeSwitch from '@/components/ThemeSwitch.vue'
import { ALARM_LEVEL, resolvePointVisual } from '@/constants/status'
import { HEAT_RAMP_STOPS, heatColorOf, heatExtentOf } from '@/utils/heatScale'
import { useMonitorStore } from '@/stores/monitor'
import { useIntegrityStore } from '@/stores/integrity'
import { useRealtimeStore } from '@/stores/realtime'
import { GRANULARITY_TEXT, REPLAY_RANGES, useReplayStore } from '@/stores/replay'
import { useTheme } from '@/composables/useTheme'
import { formatNumber, formatSigned, formatTime, fromNow } from '@/utils/format'
import { HASH_TEXT, isHashProblem } from '@/utils/assetHash'
import { frameProgress, indexFromProgress } from '@/utils/timeline'

defineOptions({ name: 'ScreenView' })

const router = useRouter()
const store = useMonitorStore()
const realtime = useRealtimeStore()
const replay = useReplayStore()
const { isDark } = useTheme()
const container = ref(null)
let viewer = null
let pointLayer = null
let heatLayer = null
let zoneLayer = null
let labelOverlay = null
let mountainScene = null
let clickHandler = null
let removePostRender = null
let pollTimer = null
let fitDebugTimer = null
let bannerTimer = null
let sceneLoadGeneration = 0
let disposed = false
let removeRenderError = null
const viewerError = ref('')
/** 取景诊断文本（模型半径 / 取景距离 / 相机高度），临时用来定位"全局视角变一个小点" */
const fitDebug = ref('')

/**
 * WebGL 上下文状态（复查清单 P1-12）。
 *
 * 为什么必须处理：显卡驱动重启、系统休眠唤醒、显存吃紧时，浏览器会**丢掉** WebGL 上下文。
 * 此时页面不会报错、不会白屏，只是画面**冻在最后一帧**——大屏看起来一切正常，
 * 值班员看到的却是几分钟前的旧画面。改造前全仓 grep webglcontextlost 零命中，
 * 唯一的"恢复办法"是有人发现不对、手动刷新页面。
 *
 * 两件事分工：
 *   · lost 时 `preventDefault()`（规范要求：不阻止默认行为，浏览器不会尝试恢复），
 *     并给出**明确提示**——冻结的画面必须被说出来；
 *   · restored 时提供「重建三维视图」：Cesium 的 viewer 在上下文丢失后不一定能自行复原，
 *     整块重建是唯一可靠的路径（数据与 SSE 连接都不受影响，只重建渲染层）。
 */
const glLost = ref(false)
const glRestored = ref(false)
let rebuildingViewer = false
let removeContextLost = null
let removeContextRestored = null

/** 监听上下文事件。canvas 元素随 viewer 重建，所以监听也要跟着重建（见 teardownViewer） */
function bindContextEvents(v) {
  const canvas = v?.scene?.canvas
  if (!canvas) return
  const onLost = (event) => {
    // 规范要求先 preventDefault，否则浏览器不会尝试恢复上下文
    event.preventDefault()
    glLost.value = true
    glRestored.value = false
    console.warn('[cesium] WebGL 上下文丢失：画面已冻结，等待重建')
  }
  const onRestored = () => {
    glRestored.value = true
    console.info('[cesium] WebGL 上下文已恢复：可点「重建三维视图」')
  }
  canvas.addEventListener('webglcontextlost', onLost, false)
  canvas.addEventListener('webglcontextrestored', onRestored, false)
  removeContextLost = () => canvas.removeEventListener('webglcontextlost', onLost)
  removeContextRestored = () => canvas.removeEventListener('webglcontextrestored', onRestored)
}

function reportViewerError(error) {
  if (disposed) return
  viewerError.value = error?.message || '请检查浏览器 WebGL 支持、硬件加速及页面资源是否加载完整。'
  console.error('[cesium] 三维视图运行失败：', error)
}

function reloadViewer() {
  window.location.reload()
}

/** 刚收到的告警横幅（12s 后自己消失） */
const alarmBanner = ref(null)

const terrainState = ref('loading') // loading | ok | failed | skipped
const imageryState = ref('loading') // loading | ion | fallback | failed
const mountainState = ref(LOCAL_SCENE_ENABLED ? 'loading' : 'skipped')
const sceneConfig = ref(null)
const sceneError = ref('')

/**
 * 资产哈希核对（P0-5）：加载模型时顺手算一遍 SHA-256，与库里登记的值比对。
 * 结果存在 integrity store 里，运维页读的是同一份——两处不能各说各话。
 */
const integrity = useIntegrityStore()
const assetCheck = computed(
  () => (store.projectId ? integrity.byProject[store.projectId] || null : null),
)
const assetProblem = computed(
  () => (assetCheck.value && isHashProblem(assetCheck.value.status) ? assetCheck.value : null),
)
const assetChipClass = computed(() => {
  if (!assetCheck.value) return 'dim'
  return isHashProblem(assetCheck.value.status) ? 'err' : 'ok'
})
/*
 * 用 `VITE_ASSET_OVERRIDE` 做本地预览时，哈希核对是**故意跳过**的
 * （库里登记的是旧资产的 SHA-256，对着新资产核对只会得到一条假告警）。
 * 这种时候顶栏就不能显示"资产未核对"——那看着像故障，演示时会被当成系统坏了。
 * 改成"预览资产"，把"我们没核对"和"核对不一致"两件事分开说。
 */
const assetChipText = computed(() => {
  if (assetCheck.value) return HASH_TEXT[assetCheck.value.status] || '资产核对'
  return ASSET_OVERRIDE ? '预览资产' : '资产未核对'
})
const assetChipTitleBase = computed(() =>
  (ASSET_OVERRIDE && !assetCheck.value
    ? `本地预览资产（已跳过 SHA-256 核对）：${ASSET_OVERRIDE}`
    : null),
)
const assetChipTitle = computed(() => {
  const r = assetCheck.value
  if (!r) return assetChipTitleBase.value || '进入场景后自动核对模型 SHA-256'
  return [
    HASH_TEXT[r.status] || r.status,
    `资产：${r.assetUrl}`,
    r.expected ? `登记：${r.expected.slice(0, 16)}…` : '登记：无',
    r.actual ? `实测：${r.actual.slice(0, 16)}…` : '',
    r.error ? `错误：${r.error}` : '',
  ].filter(Boolean).join('\n')
})
const activeRadarId = ref(null)

/**
 * 地面热力层的标度（2026-09-20 重做）：颜色与半径都按**当前这批测点**的最大绝对值归一化，
 * 所以标度要跟着数据走，并在图例里把刻度写出来——不然"颜色深一点"没有可解释的含义。
 */
const heatMax = computed(() => heatExtentOf(displayPoints.value))
const heatUnit = computed(() => store.primaryMetric?.unit || '')
const heatLegendGradient = computed(() => {
  const stops = HEAT_RAMP_STOPS.map((s) => `${s.color} ${((s.at + 1) / 2 * 100).toFixed(0)}%`)
  return `linear-gradient(90deg, ${stops.join(', ')})`
})

/** 跟随测点的浮窗 */
const popup = reactive({ visible: false, pointId: null, x: 0, y: 0 })

const TERRAIN_TEXT = {
  loading: '地形加载中',
  ok: '真实地形',
  failed: '地形降级',
  skipped: '无地形',
}
const IMAGERY_TEXT = {
  loading: '影像加载中',
  ion: '卫星影像',
  offline: '离线底色',
  fallback: '兜底底图',
  failed: '无底图',
}
const MOUNTAIN_TEXT = {
  loading: '场景加载中',
  ok: '数字孪生已加载',
  failed: '数字孪生加载失败',
  skipped: '场景未配置',
}

const sceneDescription = computed(() => {
  if (!sceneConfig.value) return '当前项目未配置数字孪生资产'
  const version = sceneConfig.value.assetVersion || '未标版本'
  return `${sceneConfig.value.assetType} · ${version}`
})

/**
 * 「当前项目没配数字孪生场景」时的备用项目（一键切过去就能看到三维场景）。
 *
 * 为什么需要它：`GET /projects` 的首位不一定是配了场景的那个项目。2026-09-17 实测到一次——
 * 列表把「西江水泥采空区」（assetType=NONE）排在了「清远山地边坡」前面，大屏默认选中前者，
 * 屏幕上只剩「场景未配置 / 离线底色」，用户看到的就是「3D 大屏打不开」。
 * 后端列表现在有了确定排序（`BaseCrudController#ordered`），但**任何**项目都可能是没配的那个，
 * 所以这里把话说清楚、并给一个能点的出口。
 *
 * 只探前 5 个候选：这是顺手给的入口，不是全库扫描；项目多的时候用户自己在项目下拉里选。
 */
const sceneAlternate = ref(null)

/** 当前项目确实是「没配场景」，而不是「加载失败」或「还没开始加载」 */
const noScene = computed(
  () => LOCAL_SCENE_ENABLED && mountainState.value === 'skipped' && !!store.projectId,
)

async function findSceneAlternate(excludeId, generation) {
  sceneAlternate.value = null
  const candidates = (store.projects || []).filter((p) => p.id !== excludeId).slice(0, 5)
  for (const p of candidates) {
    try {
      const cfg = await projectDigitalTwin(p.id)
      if (generation !== sceneLoadGeneration) return // 期间又切了项目：这一轮的结果作废
      if (cfg?.enabled && cfg?.assetUrl) {
        sceneAlternate.value = { id: p.id, name: p.name }
        return
      }
    } catch {
      // 探测失败就当它没配场景：这只是给用户的顺手入口，不该因此弹错或打断大屏
    }
  }
}

/**
 * 没配 ion token 时给一条明确的提示。
 * 不说的话，界面上只有「无地形 / 兜底底图」两个灰字——第一次跑的人（或换台机器 clone
 * 下来的人）看到的是「3D 地图加载不出来」，却不知道该配什么。token 在 `.env.local`，
 * 而那是 gitignore 的，每个人都要自己配一次。
 */
const TOKEN_HINT =
  '未配置 Cesium ion token：地形与卫星影像不可用，已降级为椭球 + 兜底底图。'
  + '开发态写 frontend/.env.local 的 VITE_CESIUM_ION_TOKEN，compose 形态写根目录 .env '
  + '并 docker compose build frontend（VITE_* 是构建期注入，restart 不生效）。'

const points = computed(() => store.enrichedPoints)
const pointSearch = ref('')
const SIDE_LIST_LIMIT = 300

/**
 * 屏幕上真正显示的那份数据：实时快照，或回放帧。
 *
 * 只在这一层切换数据源，`pointLayer` / 热力层 / 列表 / 弹窗都读它——
 * 于是「回放」不会漏掉某个角落还显示实时值（那种不一致最难解释）。
 */
const displayPoints = computed(() => {
  const source = applyPreviewPoints(points.value)   // 预览场景里把测点摆到合理位置
  const base = !replay.enabled ? source : source.map((p) => {
    const values = replay.values
    const v = values[p.id]
    return {
      ...p,
      value: v ?? null,
      metrics: { ...p.metrics, [p.metricCode]: v ?? null },
      hasData: v !== null && v !== undefined,
      // 数据过期（清单第 19 条）：判定已经在 `buildFrames` 里做完，这里只搬运。
      // 不搬的话，`resolvePointVisual` 会把这个陈旧值当成当前值去比阈值
      stale: replay.isStale(p.id),
      // 采集时间取**这个值自己的采样时刻**，不是帧时刻。前值保持时两者差着几分钟到几小时
      // （原来写的是 `replay.time`），等于把「3 小时前的读数」伪装成「这一时刻的读数」。
      // `sampleMs` 是毫秒数，`formatTime` 走 `parseTime` → `new Date(ms)`，吃这个格式
      collectTime: replay.sampleMsOf(p.id) ?? p.collectTime,
    }
  })
  // 分区聚焦：选了分区就只留这个分区的测点（3D 图层、热力、列表、弹窗都读这个 computed）
  if (!activeZone.value) return base
  return base.filter((p) => pointInZone(p, activeZone.value))
})

/** 侧栏做轻量窗口化：3D 仍绘制全部点，DOM 最多保留 300 行，并可按编码/名称定位。 */
const sidePoints = computed(() => {
  const keyword = pointSearch.value.trim().toLowerCase()
  const filtered = keyword
    ? displayPoints.value.filter((p) => `${p.code || ''} ${p.name || ''}`.toLowerCase().includes(keyword))
    : displayPoints.value
  return filtered.slice(0, SIDE_LIST_LIMIT)
})

const selected = computed(() => displayPoints.value.find((p) => p.id === popup.pointId) || null)
const radars = computed(() => sceneConfig.value?.radars || [])
const activeRadar = computed(() =>
  radars.value.find((r) => String(r.deviceId) === String(activeRadarId.value)) || null,
)

function radarSourcesOf(pointId) {
  return radars.value.flatMap((radar) =>
    (radar.targets || [])
      .filter((target) => String(target.pointId) === String(pointId))
      .map((target) => ({ radar, target })),
  )
}

const selectedRadarSources = computed(() => radarSourcesOf(popup.pointId))

function selectRadar(deviceId) {
  activeRadarId.value = deviceId
  mountainScene?.setActiveRadar(deviceId)
}

/**
 * 弹窗里的测项行：**按测项档案列**，不再写死「累计形变 / 形变速率」两行。
 * 档案取不到时退回 latest 里发现的测项（至少不至于什么都看不见）。
 */
const selectedMetricRows = computed(() => {
  const p = selected.value
  if (!p) return []
  const rows = store.metricsOf(p.id)
  if (rows.length) {
    return rows.map((m) => ({
      code: m.code,
      name: m.name,
      unit: m.unit,
      value: p.metrics?.[m.code] ?? null,
      primary: m.code === p.metricCode,
    }))
  }
  return Object.entries(p.metrics || {}).map(([code, value]) => ({
    code,
    name: code,
    unit: '',
    value,
    primary: code === p.metricCode,
  }))
})
// 首屏还没加载完时要显示「加载中」，不能先亮"暂无数据"（会让人以为系统坏了）
// 失败必须单独判——否则 !loadedAt 会永远成立，加载失败被显示成「加载中」，
// 一个不会自己结束的状态（见 stores/monitor.js 的 loadSnapshot）
const dataStatus = computed(() => {
  // 「一个项目都没加入」不是故障，是权限范围的正常结果——要单独说，别显示成「加载失败」
  if (store.noProjectReason) return store.noProjectReason
  if (store.error) return '加载失败'
  if (replay.enabled) return `回放中 ${replay.index + 1}/${replay.total}`
  if (store.loading || !store.loadedAt) return '加载中…'
  return store.dataPointCount ? '正在报数' : '暂无数据'
})

/** 热力图开关（默认开：演示时「哪片区域形变大」最直观） */
const heatOn = ref(true)
/**
 * 雷达视场扇面（每台雷达一个颜色，选中那台提亮）。与热力图一样属于「图层」，可单独关掉。
 *
 * 2026-09-23 用户要求"先把雷达扫描范围的示意线去掉" → **默认关**。
 * 关掉后画面里不再有虚线扇形、也不再画"雷达 → 目标"的连线，只留雷达名牌与扇面中心线；
 * 需要看理论视场时在右侧图例里把这一条勾回来即可（代码与其它图层都没动）。
 */
const sectorOn = ref(false)
/**
 * 垂直视场上下边界（±verticalHalfAngle）默认关闭（2026-09-20 用户反馈「为什么有三个区域」）。
 * 它和水平扇面叠在一起会被读成另一块覆盖区——需要看垂直范围时再打开，
 * 打开后也只画选中的那一台（见 digitalTwinScene 的 setVerticalVisible）。
 */
const verticalOn = ref(false)
/**
 * 地形裁剪覆盖层（P1-11 后半，默认开）。它是"这台雷达按这片地形真的能看到哪儿"：
 * 外缘跟着山脊线走。与理论视场不是一回事——理论视场是参数算出来的，这一层是地形算出来的。
 */
/** 地形裁剪覆盖层：同样是"扫描范围"示意，跟着上面那条一起默认关（用户 2026-09-23 要求）。 */
const coverageOn = ref(false)

/** 当前主测项是不是「速率」类——点符号用它区分（见 pointLayer.js 的说明） */
const isRateMetric = computed(() => String(store.primaryMetricCode || '').includes('rate'))
/** 回放滑块用 0~1000 的整数步长，避免浮点抖动 */
const replayProgress = computed({
  get: () => frameProgress(replay.index, replay.total),
  set: (v) => replay.seek(indexFromProgress(v, replay.total)),
})
const replayFrameInfo = computed(() => {
  if (!replay.enabled) return ''
  // 只能数**没过期**的点（第 19 条）：把陈旧值算进「有数据」，设备全断了屏幕上还写着
  // 「7 个测点有数据」——那正是本条要消掉的虚高。过期的单独说，不然读者不知道人去哪了
  const base = `${replay.currentFreshCount} 个测点有数据`
  return replay.currentStaleCount ? `${base}（${replay.currentStaleCount} 个已过期）` : base
})

/** 当前这批帧的粒度，给用户一个「为什么 7 天才这么几个点」的答案 */
const replayGranularityText = computed(
  () => `按${GRANULARITY_TEXT[replay.granularity] || replay.granularity}聚合`,
)

const LEGEND = [
  { key: 'normal', label: '正常', color: '#35b37e' },
  { key: 'over-threshold', label: '达到配置阈值', color: '#e6a23c' },
  { key: 'alarm', label: '告警中', color: '#f56c6c' },
  { key: 'suspect', label: '数据可疑', color: '#e6a23c' },
  { key: 'disappeared', label: '目标失联', color: '#8a94a6' },
  { key: 'stale', label: '数据过期（值为最后一次读数）', color: '#a3acbb' },
  { key: 'no-data', label: '暂无数据', color: '#c0c4cc' },
  // 雷达侧的三档（P1-11）：与上面的测点状态并列但不混同——它们说的是**标定与通视**，
  // 不是测值状态。颜色与 digitalTwinScene 里目标连线的三档一一对应。
  { key: 'target-verified', label: '已核验目标（标定有效且通视）', color: '#55e6a5' },
  { key: 'target-unverified', label: '待核验目标（通视但未标定）', color: '#f4c95d' },
  { key: 'target-blocked', label: '被遮挡目标（通视校验未过）', color: '#ff6b6b' },
]

/**
 * 当前选中雷达的核验统计（P1-11）。
 *
 * 为什么值得单独算：面板上原来只写「N 个已绑定目标」——那是**绑定数**，
 * 不是"有多少个真的能采信"。绑了 6 个、其中 2 个被山体挡住，看的人却以为 6 个都在监测。
 * 三档的定义与目标连线的配色逐条对应（见 cesium/digitalTwinScene.js）。
 */
const radarTargetStats = computed(() => {
  const targets = activeRadar.value?.targets || []
  const verified = targets.filter((t) => String(t.calibrationStatus || '').toUpperCase() === 'ACTIVE' && t.lineOfSight === true)
  const blocked = targets.filter((t) => t.lineOfSight !== true)
  return {
    total: targets.length,
    verified: verified.length,
    blocked: blocked.length,
    pending: targets.length - verified.length - blocked.length,
  }
})

function openPopup(pointId) {
  popup.pointId = pointId
  popup.visible = true
  const source = radarSourcesOf(pointId)[0]
  if (source) selectRadar(source.radar.deviceId)
}

function closePopup() {
  popup.visible = false
}

// 模板里不要直接摸 viewer（它是普通变量、首帧还是 null），统一走这两个包装函数
function focusPoint(point) {
  if (viewer) flyToPoint(viewer, point)
  openPopup(point.id)
}

function resetView() {
  // 2026-09-21：不再依赖"创建场景时那份闭包"（Vite 热更新会保留旧场景实例 → 走旧取景逻辑），
  // 直接调模块级 fitSceneView：每次都从当前场景里真实加载的模型量半径再取景。
  clearZone()   // 「回到全局视角」= 回到全区（分区选择一并清掉）
  if (viewer && fitSceneView(viewer, { duration: 1.4 })) return
  if (mountainScene) mountainScene.flyHome()
  else if (viewer) flyToPoints(viewer, store.points)
}

/* ───────────────────────── 分区导航（2026-09-23）─────────────────────────
 * 交互：全局视角看到若干分区（贴地椭圆 + 浮空名称）→ 点一个 → 相机聚焦到该分区、
 * 左侧测点列表与 3D 测点只剩这个分区的 → 点测点/雷达看详情 → 「回到全局视角」回全区。
 * 分区数据来自 `public/zones.json`（换场址只换这份文件，代码不动）。
 */
const zones = ref([])
const activeZone = ref(null)
/** 分区/预览配置的加载 promise：场景创建前要 await 它，否则首屏可能用不到设备摆放 */
let previewConfigPromise = null
/** 预览资产的"测点台面"高度（相对锚点，米）；由 public/preview.json 提供，用于把标点放到地表 */
const previewGroundHeight = ref(NaN)
/**
 * 预览资产**地面**的平均高度（相对锚点，米）：决定整块地要下移多少才能坐在椭球面（= 远景影像面）上。
 * 与上面那个"台面高度"分开存，理由见 loadZones 里的注释。
 */
const previewTerrainHeight = ref(NaN)
/** 预览用设备摆放（public/devices.json）：把雷达摆到场景里合理位置并调好朝向 */
const previewDevices = ref(null)

/**
 * 把预览设备摆放应用到场景配置上（**只改内存里的 config**，数据库档案不动）。
 * 为什么需要：清远那套设备坐标是给边坡场址定的，挂到别的演示资产上会落在不相干的位置；
 * 这里按"雷达能罩住自己绑定的目标"重新算落位与朝向。
 */
function applyPreviewDevices(config) {
  const devices = previewDevices.value
  if (!devices?.radars || !config?.radars?.length) return
  const anchorLon = Number(config.anchorLongitude)
  const anchorLat = Number(config.anchorLatitude)
  if (!Number.isFinite(anchorLon) || !Number.isFinite(anchorLat)) return
  const mPerLon = 111412.84 * Math.cos((anchorLat * Math.PI) / 180)
    - 93.5 * Math.cos((3 * anchorLat * Math.PI) / 180)
  const mPerLat = 111132.92 - 559.82 * Math.cos((2 * anchorLat * Math.PI) / 180) + 1.175
  const ground = (Number(config.anchorHeight) || 0)
    + (Number.isFinite(previewGroundHeight.value) ? previewGroundHeight.value : 0)
  for (const radar of config.radars) {
    const override = devices.radars[radar.code]
    if (!override) continue
    radar.longitude = anchorLon + (Number(override.eastM) || 0) / mPerLon
    radar.latitude = anchorLat + (Number(override.northM) || 0) / mPerLat
    radar.altitude = ground
    if (Number.isFinite(Number(override.headingDegrees))) radar.headingDegrees = Number(override.headingDegrees)
    if (Number.isFinite(Number(override.pitchDegrees))) radar.pitchDegrees = Number(override.pitchDegrees)
  }
}

/**
 * 预览用测点摆放：把档案坐标换成"演示场景里说得通"的位置（只影响显示，不改数据库）。
 * 没配的测点原样返回，所以换场址只改 devices.json 就行。
 */
function applyPreviewPoints(list) {
  const devices = previewDevices.value
  const config = sceneConfig.value
  if (!devices?.points || !list?.length || !config) return list
  const anchorLon = Number(config.anchorLongitude)
  const anchorLat = Number(config.anchorLatitude)
  if (!Number.isFinite(anchorLon) || !Number.isFinite(anchorLat)) return list
  const mPerLon = 111412.84 * Math.cos((anchorLat * Math.PI) / 180)
    - 93.5 * Math.cos((3 * anchorLat * Math.PI) / 180)
  const mPerLat = 111132.92 - 559.82 * Math.cos((2 * anchorLat * Math.PI) / 180) + 1.175
  const ground = (Number(config.anchorHeight) || 0)
    + (Number.isFinite(previewGroundHeight.value) ? previewGroundHeight.value : 0)
  return list.map((point) => {
    const override = devices.points[point.code]
    if (!override) return point
    return {
      ...point,
      longitude: anchorLon + (Number(override.eastM) || 0) / mPerLon,
      latitude: anchorLat + (Number(override.northM) || 0) / mPerLat,
      altitude: ground,
    }
  })
}

/**
 * zones.json 存的是本地米（相对资产锚点），而资产被放到**当前项目**的锚点上，
 * 所以经纬度要按场景锚点现算。分区列表、3D 圈、过滤判定全部读这一份（避免三处口径不一致）。
 */
const zonesWithGeo = computed(() => {
  const anchorLon = Number(sceneConfig.value?.anchorLongitude)
  const anchorLat = Number(sceneConfig.value?.anchorLatitude)
  return zones.value.map((zone) => {
    if (!Number.isFinite(anchorLon) || !Number.isFinite(anchorLat)) return zone
    const mPerLon = 111412.84 * Math.cos((anchorLat * Math.PI) / 180)
      - 93.5 * Math.cos((3 * anchorLat * Math.PI) / 180)
    const mPerLat = 111132.92 - 559.82 * Math.cos((2 * anchorLat * Math.PI) / 180) + 1.175
    return {
      ...zone,
      longitude: anchorLon + (Number(zone.eastM) || 0) / mPerLon,
      latitude: anchorLat + (Number(zone.northM) || 0) / mPerLat,
    }
  })
})

/**
 * 读取三份"预览配置"（分区 / 资产地面高度 / 设备摆位）。
 *
 * 2026-09-23 修掉一个真事故：三份文件以前写在一个 try 里，并且**边读边应用**——
 * `applyZones()` 需要场景锚点（把 zones.json 的本地米换算成经纬度），而这里是在
 * `createViewer()` 之后立刻调用的，`sceneConfig` 往往还是 null → 分区经纬度是 undefined
 * → `Cartesian3.fromDegrees(NaN)` 抛 `normalized result is not a number` → 整个函数中断，
 * 后面两份文件根本不读。表现出来的就是用户看到的：**分区里点数全是 0、雷达和测点
 * 还停在档案坐标上（跑到别的城市去了）**，而分区标签却画出来了（异常发生在它之后）。
 *
 * 现在改成：①三份文件各自独立 try，互不牵连；②**先全部读进来，再统一应用**，
 * 与场景就绪的先后顺序无关；③锚点没就绪时应用是无害的空操作，锚点到位后
 * `applyZones()` 会由 loadProjectScene 再调一次（见那里的"场景锚点变了"注释）。
 */
async function loadZones() {
  const [zonesResult, previewResult, devicesResult] = await Promise.all([
    fetchJson('/zones.json'),
    fetchJson('/preview.json'),
    fetchJson('/devices.json'),
  ])
  if (Array.isArray(zonesResult?.zones)) {
    zones.value = zonesResult.zones
  }
  const ground = Number(previewResult?.groundHeightM)
  if (Number.isFinite(ground)) previewGroundHeight.value = ground
  /*
   * 资产地面（相对锚点）与"测点台面"是两回事，必须分开存：
   *   · `groundHeightM`（15.3 m）是测点/雷达标点所在的那一层台面 —— 决定立柱起点；
   *   · `terrainSurfaceHeightM`（≈5.4 m）是这块地**地面**的平均高度 —— 决定整块地要下移多少
   *     才能坐在椭球面（= 远景影像面）上，见 loadProjectScene 里的说明。
   * 现场实测（深度拾取 7 个测点脚下）：3.9 / 4.8 / 5.0 / 5.2 / 6.5 / 12.1 m，均值 5.4。
   */
  const surface = Number(previewResult?.terrainSurfaceHeightM)
  if (Number.isFinite(surface)) previewTerrainHeight.value = surface
  if (devicesResult?.radars || devicesResult?.points) previewDevices.value = devicesResult
  // 三份配置齐了再落地：分区（贴地椭圆/标签）、测点贴地、雷达落位
  applyZones()
  plantMastsOnTerrain().catch(() => {})
}

/** 读一份 JSON；失败只记一条日志并返回 null，不让整个加载流程陪葬 */
async function fetchJson(url) {
  try {
    const response = await fetch(url, { cache: 'no-cache' })
    if (!response.ok) {
      console.warn('[scene] 预览配置缺失：', url, response.status)
      return null
    }
    return await response.json()
  } catch (error) {
    console.warn('[scene] 预览配置读取失败：', url, error?.message || error)
    return null
  }
}

/** 场景锚点高度变了（换项目/换资产）要重新算分区的贴地高度 */
function applyZones() {
  if (!zoneLayer) return
  const config = sceneConfig.value
  zoneLayer.setZones(zonesWithGeo.value, config?.anchorHeight || 0)
  zoneLayer.setActive(activeZone.value?.id || null)
}

/** 点某个分区：聚焦 + 过滤（过滤在 displayPoints 里做） */
function selectZone(zone) {
  activeZone.value = zone
  zoneLayer?.setActive(zone.id)
  flyToZone(zone)
}

function clearZone() {
  if (!activeZone.value) return
  activeZone.value = null
  zoneLayer?.setActive(null)
}

/** 相机飞进分区：与 fitSceneView 同一套做法（留在场址 lookAt 变换里，别绕地球转） */
function flyToZone(zone) {
  const config = sceneConfig.value
  if (!viewer || !config) return
  const anchor = Cesium.Cartesian3.fromDegrees(
    Number(config.anchorLongitude), Number(config.anchorLatitude), Number(config.anchorHeight) || 0,
  )
  const frame = Cesium.Transforms.eastNorthUpToFixedFrame(anchor)
  const local = new Cesium.Cartesian3(
    Number(zone.eastM) || 0,
    Number(zone.northM) || 0,
    (Number(zone.groundHeightM) || 0) + 12,
  )
  const target = Cesium.Matrix4.multiplyByPoint(frame, local, new Cesium.Cartesian3())
  const camera = zone.camera || {}
  // 动画（2026-09-23 用户要求"直接平移过去"）：直线插值位置与朝向，不走航空式飞行弧线
  panLookAt(
    viewer, target,
    Number(camera.heading ?? 315), Number(camera.pitch ?? -26), Number(camera.rangeM ?? 420),
    1100,
  )
}

/** 测点是否落在分区里（半径判定；分区是圆形的） */
function pointInZone(point, zone) {
  const lon = Number(point?.longitude)
  const lat = Number(point?.latitude)
  if (!Number.isFinite(lon) || !Number.isFinite(lat)) return false
  const mPerLon = 111412.84 * Math.cos((lat * Math.PI) / 180) - 93.5 * Math.cos((3 * lat * Math.PI) / 180)
  const mPerLat = 111132.92 - 559.82 * Math.cos((2 * lat * Math.PI) / 180) + 1.175
  const dx = (lon - Number(zone.longitude)) * mPerLon
  const dy = (lat - Number(zone.latitude)) * mPerLat
  return Math.hypot(dx, dy) <= Number(zone.radiusM || 0)
}

/** 分区里有多少个测点（列表上给个数，选之前就知道值不值得点进去） */
function zonePointCount(zone) {
  // 用"预览摆位后"的测点算：预览模式下测点位置来自 devices.json，与档案坐标不同
  return applyPreviewPoints(points.value).filter((point) => pointInZone(point, zone)).length
}

/** 窗口尺寸变了要重算取景（FOV/宽高比变了，同一个距离就不"刚好装下"了） */
function onWindowResize() {
  if (viewer) {
    try {
      fitSceneView(viewer)
    } catch {
      /* 取景失败不影响其它功能 */
    }
  }
}

/** 每帧把浮窗贴到测点的屏幕位置上；点转到背面或出屏就藏起来 */
function trackPopup() {
  if (!popup.visible || !popup.pointId) return
  const entity = viewer.entities.getById(`point-${popup.pointId}`)
  if (!entity) return
  const position = entity.position?.getValue(viewer.clock.currentTime)
  if (!position) return
  const screen = Cesium.SceneTransforms.worldToWindowCoordinates(viewer.scene, position)
  if (!screen) {
    popup.visible = false
    return
  }
  popup.x = screen.x
  popup.y = screen.y
}

async function loadData() {
  if (!store.points.length) {
    await store.loadSnapshot()
  } else {
    await store.refreshLatest()
  }
  if (viewer && store.points.length && !LOCAL_SCENE_ENABLED) {
    flyToPoints(viewer, store.points, { duration: 1.8 })
  }
}

/** 正在建场景的项目 id：同一项目的并发请求合并成一次（见 loadProjectScene 的说明） */
let sceneLoadInFlight = null

/**
 * 按当前项目加载三维场景。**同一项目的并发请求只建一次**（2026-09-23）。
 *
 * 为什么需要：首屏有两条路径同时走到这里——`loadData()` 把 projectId 写进 store 会触发
 * 项目 watch，`initViewer()` 自己在 loadData 之后又显式调一次。两边都 await 网络与 GLB，
 * 于是同一份场景被建两遍：多花一倍带宽，而且两套实体在同一帧里互相覆盖/删除
 * （雷达名牌、视场覆盖层整个消失，就是这么来的；见 digitalTwinScene 里 token 的说明）。
 *
 * 去重是安全的：重复请求的入参完全相同，结果必然相同；真正的项目切换（不同 id）
 * 仍然走下面 generation 的"后发者获胜"。
 */
async function loadProjectScene(projectId) {
  if (sceneLoadInFlight === projectId) return null
  sceneLoadInFlight = projectId
  try {
    return await doLoadProjectScene(projectId)
  } finally {
    if (sceneLoadInFlight === projectId) sceneLoadInFlight = null
  }
}

/**
 * 场景加载的实际实现。generation 用于解决快速切换项目时的异步竞态：
 * 后发请求获胜，迟到的旧场景立即销毁，绝不覆盖新项目。
 */
async function doLoadProjectScene(projectId) {
  const generation = ++sceneLoadGeneration
  sceneError.value = ''
  sceneConfig.value = null
  activeRadarId.value = null
  sceneAlternate.value = null
  mountainScene?.destroy()
  mountainScene = null
  window.__digitalTwinScene = null

  if (!LOCAL_SCENE_ENABLED || !projectId || !viewer) {
    mountainState.value = 'skipped'
    if (viewer && store.pointsOfProject.length) flyToPoints(viewer, store.pointsOfProject)
    return null
  }

  mountainState.value = 'loading'
  try {
    const config = await projectDigitalTwin(projectId)
    if (generation !== sceneLoadGeneration) return null
    // 等分区/预览配置就绪：雷达落位要用到 previewGroundHeight 与 devices.json
    await previewConfigPromise?.catch(() => {})
    /*
     * 预览锚点覆盖（2026-09-23）：演示资产放在"清远锚点"上，而远景层是瑞士正射——
     * 两者相差约一万公里，于是四周只命中 z0~z11 的全球低清层（糊成一片绿）。
     * 把锚点也搬到资产真正的场址，资产/远景/分区/设备就全对齐了。
     * 只在本地预览用；数据库里的项目锚点不动。
     */
    const anchorOverride = String(import.meta.env.VITE_ANCHOR_OVERRIDE || '').split(',').map(Number)
    if (anchorOverride.length === 3 && anchorOverride.every(Number.isFinite)) {
      config.anchorLongitude = anchorOverride[0]
      config.anchorLatitude = anchorOverride[1]
      config.anchorHeight = anchorOverride[2]
    }
    // 本地试验：把资产临时指到另一份 GLB（受光版），并且跳过哈希核对——
    // 数据库里记的是旧资产的 SHA-256，对着新资产核对只会得到一条假告警。
    // 真正上线要走新版本号迁移把 asset_sha256 一起更新。
    const overridden = Boolean(ASSET_OVERRIDE) && config?.enabled
    if (overridden) config.assetUrl = ASSET_OVERRIDE
    /*
     * 把地块落到"渲染出来的地面"上 —— 修 2026-09-23 用户反馈的"模型飘在天上"。
     *
     * 现象：压低相机（俯角接近水平）时，模型像一块悬空的板子浮在地面影像之上，
     * 板子边缘就是一道断崖。
     *
     * 根因（已量化，不是猜的）：
     *   · viewer 建的是 `EllipsoidTerrainProvider`，**椭球面在 0 m**，远景影像就铺在它上面；
     *   · 而场址锚点记的是真实海拔（演示场址 395 m），资产挂在锚点坐标系里；
     *   · 深度拾取实测：模型自己的地面在锚点之上 4~12 m（均值 ≈5.4 m），
     *     也就是模型地面实际在 400 m 左右，比影像面高 **395 m** —— 这就是那道断崖。
     *
     * 修法：把锚点高度改成"让资产地面正好落在椭球面上"，
     * 即 `anchorHeight = -地表高度(相对锚点)`。这样：
     *   · 模型地面 ≈ 0，与远景影像同一个平面，断崖消失；
     *   · 测点/雷达/分区/立柱全都按 `anchorHeight + 相对高度` 算，跟着一起平移，不会错位；
     *   · 经纬度完全不动，只是把整块地整体下移到 Cesium 真正画出来的地面上。
     *
     * 接了真实地形（`VITE_FARFIELD_TERRAIN=1`）时**不能**这么做——那时椭球/地形是真实的，
     * 锚点海拔必须保持原样。
     *
     * 下移量取 `preview.json` 的 `terrainSurfaceHeightM`，且**只对演示资产生效**：
     * 流水线产出的地形资产，其局部原点本来就在地面基准上（相对高度 ≈0），别的项目按 0 处理即可。
     */
    if (!FARFIELD_TERRAIN_ENABLED) {
      const surface = overridden ? Number(previewTerrainHeight.value) : 0
      config.anchorHeight = -(Number.isFinite(surface) ? surface : 0)
    }
    if (!config?.enabled) {
      mountainState.value = 'skipped'
      /*
       * 该项目没配三维场景（2026-09-23 修）：以前这里直接 return，旧场景已被 destroy，
       * 于是画面全黑、相机停在 41m 那种无意义的位置——用户看到的就是"切项目出 bug"。
       * 现在：清掉分区、把相机放到该项目测点的上方（没有测点就退到全球视角），
       * 并在界面上保留"未配置场景"的说明（那条提示本来就有）。
       */
      clearZone()
      zoneLayer?.setZones([], 0)
      // 上一场可能把相机留在"某个场址的 lookAt 坐标系"里——不清掉的话，
      // 下一次切回有场景的项目时，取景会在这个旧坐标系里算，表现就是"跑到旧的地方去"
      try {
        viewer?.camera.lookAtTransform(Cesium.Matrix4.IDENTITY)
      } catch { /* 忽略 */ }
      if (store.pointsOfProject.length) flyToPoints(viewer, store.pointsOfProject)
      else if (viewer) {
        viewer.camera.flyTo({
          destination: Cesium.Cartesian3.fromDegrees(113.05133, 23.75946, 2.0e6),
          duration: 1.4,
        })
      }
      // 顺手找一个配了场景的项目：这不是全库扫描，只是给「当前项目没配」的人一个能点的出口
      findSceneAlternate(projectId, generation)
      return null
    }
    // 资产哈希核对（P0-5）与模型加载并行：核对只是"报一条"，
    // 既不该拖慢首屏，也不该拦住场景——模型真坏了也要先把现场显示出来。
    if (!overridden) integrity.verify(config).catch(() => {})
    // 预览设备摆放：把雷达落到场景里合理的位置、朝向对着自己的目标（只改内存 config）
    applyPreviewDevices(config)
    // 覆盖层几何与资产加载**并行**取：它是一次几十毫秒的高程场计算，但也不该串在首屏前面。
    // 失败（老后端没有这个端点 / 没导出高程场）就让 radar.coverage 保持 undefined，
    // 场景层会只画理论视场——不冒充、不报错拦屏。
    const coverageTask = Promise.all((config.radars || []).map((radar) =>
      deviceCoverage(radar.deviceId)
        .then((coverage) => Object.assign(radar, { coverage }))
        .catch(() => radar),
    ))
    const scene = await createDigitalTwinScene(viewer, config)
    if (generation !== sceneLoadGeneration) {
      scene.destroy()
      return null
    }
    mountainScene = scene
    sceneConfig.value = config
    applyZones()   // 场景锚点变了，分区贴地高度要跟着重算
    // 场景换了，脚下的地面也换了：重新把测点贴到地表（本地资产不在 terrainProvider 里，必须贴模型）
    plantMastsOnTerrain().catch(() => {})
    if (config.radars?.length) selectRadar(config.radars[0].deviceId)
    // 新场景要继承当前的图层开关状态：关掉「视场扇面」后切项目，
    // 不该因为新建了场景就自己又亮回来
    scene.setSectorVisible(sectorOn.value)
    scene.setVerticalVisible(verticalOn.value)
    scene.setCoverageVisible(coverageOn.value)
    coverageTask.catch(() => {})
    mountainState.value = 'ok'
    pointLayer?.setLabelDistance(config.labelDistance)
    heatLayer?.sync(displayPoints.value, { visible: heatOn.value, selectedId: popup.pointId })
    // 进场取景（2026-09-21 第六版）：**只调用模块级 fitSceneView**，不再走 scene.flyHome。
    // 原因：flyHome 是"创建场景那一刻的闭包"，任何一步异常都会把这一整段 try 打断
    // （表现为"模型能看见、但相机停在默认全球视角"）。fitSceneView 独立、可重复调用，
    // 并且它自己装相机边界（进场 = 全景 = 最远距离，三者同一个数）。
    try {
      fitSceneView(viewer, { duration: 1.8 })   // 换项目/换场景：平滑推入，别硬切
    } catch (error) {
      console.warn('[cesium] 进场取景失败（不影响场景显示）：', error)
    }
    // 双保险：模型解析完成可能比"场景函数返回"晚一拍，这里再补一次取景。
    // 放在 setTimeout 里且单独 try/catch —— 前面的任何异常都不影响它执行。
    setTimeout(() => {
      if (disposed || !viewer) return
      try {
        fitSceneView(viewer)
      } catch {
        /* 忽略 */
      }
    }, 1200)
    window.__digitalTwinScene = scene
    return scene
  } catch (error) {
    if (generation !== sceneLoadGeneration) return null
    mountainState.value = 'failed'
    sceneError.value = error?.message || String(error)
    console.error('[cesium] 数字孪生场景加载失败：', error?.message || error, error?.stack || '(无堆栈)')
    if (store.pointsOfProject.length) flyToPoints(viewer, store.pointsOfProject)
    return null
  }
}

/**
 * 手动刷新：回放态刷的是历史帧（否则刷完画面纹丝不动，像是按钮坏了）；
 * 实时态刷最新值，推送若已放弃重连（closed）顺带把它拉起来。
 */
function refresh() {
  if (replay.enabled) return replay.load()
  if (realtime.status === 'closed') realtime.reconnect()
  return loadData()
}

function bannerColor(evt) {
  return ALARM_LEVEL[evt?.level]?.color || '#f56c6c'
}

function bannerLevelText(evt) {
  return ALARM_LEVEL[evt?.level]?.label || evt?.level || '告警'
}

/** 设备告警没有测点，横幅要显示设备码 —— 按 alarmType 决定渲染哪一个（契约 §4） */
function bannerTarget(evt) {
  return evt?.pointCode || evt?.deviceCode || '—'
}

/*
 * 告警事件 → 3D 上闪一下 + 顶部横幅。
 *
 * 只看 `recentAlarms[0]`：数据源每次都是**换一个新数组**（见 monitor.applyAlarm），
 * 所以同一个点重复升级也会重新触发，不会因为「值没变」被 watch 吞掉。
 */
watch(
  () => store.recentAlarms[0],
  (evt) => {
    if (!evt) return
    if (evt.pointId) pointLayer?.flash(evt.pointId)
    alarmBanner.value = evt
    if (bannerTimer) clearTimeout(bannerTimer)
    bannerTimer = setTimeout(() => {
      alarmBanner.value = null
    }, 12000)
  },
)

/**
 * 建立 viewer 与它的全部渲染层。
 *
 * 抽成函数是为了 P1-12 的"重建"：WebGL 上下文丢失后要能**把这一坨重新做一遍**，
 * 而卸载路径（onBeforeUnmount）与重建路径共用 {@link teardownViewer}。
 * 刻意不把 realtime.start() 与轮询定时器放进来——它们与渲染无关，
 * 重建时不能重复启动（会多出一个定时器、两条 SSE）。
 */
async function initViewer() {
  try {
    viewer = createViewer(container.value)
    removeRenderError = viewer.scene.renderError.addEventListener((_scene, error) => reportViewerError(error))
    bindContextEvents(viewer)
    zoneLayer = createZoneLayer(viewer, { onSelect: selectZone })
    /*
     * 测点标签把分区名标签当**外部障碍**：屏幕空间避让时先给分区名让位。
     * 这里传的是取值函数而不是数组——分区层每帧都在重算自己的位置，
     * 取值的时刻必须在避让那一瞬间（见 pointLayer 的 postRender 循环）。
     */
    pointLayer = createPointLayer(viewer, {
      obstacles: () => zoneLayer?.labelRects() || [],
    })
    heatLayer = createHeatmapLayer(viewer)
    /*
     * 标签引线走**屏幕空间叠加层**（2D canvas 压在 Cesium 画布上）：
     * 测点的水滴/圆点/标签都设了 `disableDepthTestDistance: Infinity`（永远画在最上层），
     * 引线作为它们的附属，用同一套策略才自洽——用 3D 折线会被山体整段遮掉（详见 labelOverlay.js）。
     */
    labelOverlay = createLabelOverlay(viewer)
    labelOverlay.setProvider(() => [
      ...(pointLayer?.leaderSegments() || []),
      ...(zoneLayer?.leaderSegments() || []),
    ])
    previewConfigPromise = loadZones()
    // 暴露到全局，便于在浏览器控制台排查（也方便后续做演示调试）
    window.__viewer = viewer
    window.__Cesium = Cesium
    // 标签避让的诊断入口（无头验证脚本读它：window.__pointLayer.labelDiag()）
    window.__pointLayer = pointLayer
    window.__zoneLayer = zoneLayer
    window.__labelOverlay = labelOverlay

    clickHandler = new Cesium.ScreenSpaceEventHandler(viewer.scene.canvas)
    clickHandler.setInputAction((movement) => {
      const picked = viewer.scene.pick(movement.position)
      const pointId = pointLayer.pickId(picked)
      if (pointId) {
        openPopup(pointId)
      } else {
        const radarId = mountainScene?.pickRadarId(picked)
        if (radarId != null) {
          selectRadar(radarId)
          closePopup()
        } else {
          closePopup()
        }
      }
    }, Cesium.ScreenSpaceEventType.LEFT_CLICK)

    viewer.scene.postRender.addEventListener(trackPopup)
    removePostRender = () => viewer?.scene.postRender.removeEventListener(trackPopup)

    // 先出数据、再升级地形/影像：任何一个环节慢，页面都已经有画面了
    await loadData()
    if (disposed) return
    await loadProjectScene(store.projectId)
    if (disposed) return
    setupImagery(viewer).then((s) => {
      if (!disposed) imageryState.value = s
    }).catch(reportViewerError)
    setupTerrain(viewer).then(async (s) => {
      if (disposed) return
      terrainState.value = s
      if (s === 'ok') {
        await plantMastsOnTerrain()
      }
    }).catch(reportViewerError)
  } catch (error) {
    reportViewerError(error)
  }
}

/**
 * 销毁渲染层并解绑事件。**与 onBeforeUnmount 共用**：重建时若漏掉某一项，
 * 旧 viewer 的监听/图层会留在内存里继续吃帧（实测过一次：漏解绑 postRender，
 * 页面帧率随重建次数线性下降）。
 */
function teardownViewer() {
  removePostRender?.()
  removePostRender = null
  removeRenderError?.()
  removeRenderError = null
  removeContextLost?.()
  removeContextLost = null
  removeContextRestored?.()
  removeContextRestored = null
  clickHandler?.destroy()
  clickHandler = null
  heatLayer?.destroy()
  heatLayer = null
  zoneLayer?.destroy()
  zoneLayer = null
  labelOverlay?.destroy()
  labelOverlay = null
  pointLayer?.destroy()
  pointLayer = null
  mountainScene?.destroy()
  mountainScene = null
  sceneConfig.value = null
  activeRadarId.value = null
  if (viewer) {
    viewer.destroy()
  }
  window.__viewer = null
  window.__digitalTwinScene = null
  window.__pointLayer = null
  window.__zoneLayer = null
  viewer = null
}

/**
 * 重建三维视图（P1-12 的按钮）：丢掉旧 viewer，按同一套初始化流程再来一遍。
 * 数据（store）、SSE 连接、回放状态都不动——它们与 WebGL 上下文无关。
 */
async function rebuildViewer() {
  if (rebuildingViewer || disposed) return
  rebuildingViewer = true
  try {
    teardownViewer()
    glLost.value = false
    glRestored.value = false
    viewerError.value = ''
    await initViewer()
  } finally {
    rebuildingViewer = false
  }
}

onMounted(async () => {
  await initViewer()

  // 实时推送的连接归 store（它能覆盖到本页这个顶层路由），这里只负责唤醒。
  // 放在 initViewer 之外：重建视图不该重连推送、也不该多起一个定时器。
  realtime.start()
  window.addEventListener('resize', onWindowResize)
  let tick = 0
  pollTimer = setInterval(() => {
    tick += 1
    if (!realtime.isLive) {
      store.refreshLatest()
    } else if (tick % 4 === 0) {
      store.refreshLatest()
    }
  }, 15000)
  // 取景诊断（2026-09-21）：把"模型半径 / 取景距离 / 相机离地高度"直接显示在界面上，
  // 免得只能靠猜。VITE_DEBUG_CAMERA=0 可关掉。
  if (String(import.meta.env.VITE_DEBUG_CAMERA ?? '1') !== '0') {
    fitDebugTimer = setInterval(() => {
      const f = window.__sceneFit || {}
      const v = window.__viewer
      let camH = 0
      let camDist = 0
      let liveRadius = 0
      if (v) {
        const carto = Cesium.Cartographic.fromCartesian(v.camera.positionWC)
        camH = carto ? Math.round(carto.height) : 0
        const prims = v.scene.primitives
        for (let i = 0; i < prims.length; i += 1) {
          /*
           * 取半径要**整段包住**（2026-09-23）：切到"没配场景"的项目时，
           * `createDigitalTwinScene` 的 destroy 会把模型从 primitives 里摘掉，
           * 而 Model 的 boundingSphere 在被销毁后再读会抛
           *   DeveloperError（Model.get: texture 已经没了）
           * 这个诊断定时器每秒跑一次，异常就变成"每秒刷一条红字"，很难看出真正原因。
           * 诊断行本身不该有任何抛异常的能力——读不到就当读不到。
           */
          try {
            const r = Number(prims.get(i)?.boundingSphere?.radius)
            if (Number.isFinite(r) && r > 0) { liveRadius = Math.round(r); break }
          } catch { /* 已销毁的图元：忽略 */ }
        }
      }
      fitDebug.value = `模式=${f.coordinateMode || '?'} 模型半径=${liveRadius || f.radius || '?'}${liveRadius ? '(场景实测)' : ''} 取景距离=${f.lastRange || '?'} 相机离地=${camH}m`
    }, 1000)
  }
})

/*
 * 兜底轮询（3b：数据主路是 SSE，见 stores/realtime.js 与 stores/monitor.js）：
 *   - 流断了 → 15s 一次补齐，界面照常"活着"（宁可旧一点，也不能停在那一刻）；
 *   - 流正常 → 60s 才对一次账（每 4 跳一次），兜住推送覆盖不到的变化
 *     （新建测点、漏事件）。比后端产出周期刷得更快没有意义，只会白刷接口。
 */

/**
 * 场地就绪后，把立柱起点从"档案高程"换到**脚下的地表**。
 *
 * 2026-09-23（用户反馈"标点和地图不适配"）踩坑记录：
 *   · `sampleTerrainMostDetailed(viewer.terrainProvider, …)` 采的是**地球椭球/全球地形**，
 *     而我们的地面是一个 GLB 资产（不在地形提供者里）→ 测点悬空；
 *   · 改用 `scene.clampToHeightMostDetailed` 也不行：它对普通 Model primitive 不生效，
 *     实测返回 -63079 m（把点甩到地心）；
 *   · 最终做法：本地资产由流水线算出的**地面高度**（`public/preview.json`，随资产一起生成）
 *     + 场景锚点高度 → 直接算出测点该站的高度。资产换场址时这份 JSON 跟着换即可。
 */
async function plantMastsOnTerrain() {
  const list = applyPreviewPoints(store.points).filter(
    (p) => Number.isFinite(Number(p.longitude)) && Number.isFinite(Number(p.latitude)),
  )
  if (!list.length) return
  try {
    const preview = previewGroundHeight.value
    const anchorHeight = Number(sceneConfig.value?.anchorHeight) || 0
    if (Number.isFinite(preview)) {
      // 本地预览资产：用资产地形的地面高度（相对锚点）直接算绝对高度
      const ground = anchorHeight + preview
      pointLayer?.applyTerrainHeights(list.map((p) => [p.id, ground]))
      return
    }
    const cartos = list.map((p) => Cesium.Cartographic.fromDegrees(Number(p.longitude), Number(p.latitude)))
    const sampled = await Cesium.sampleTerrainMostDetailed(viewer.terrainProvider, cartos)
    pointLayer?.applyTerrainHeights(sampled.map((c, i) => [list[i].id, c.height]))
  } catch (error) {
    console.warn('[cesium] 贴地失败，立柱沿用档案高程：', error?.message || error)
  }
}

watch(
  displayPoints,
  (list) => {
    pointLayer?.sync(list)
    // 标度由热力层自己按这批测点算（见 heatmapLayer.sync 的注释：传参漏传过一次，全部被清掉）
    heatLayer?.sync(list, { visible: heatOn.value, selectedId: popup.pointId })
  },
  { deep: true, immediate: true },
)

watch(heatOn, (on) => heatLayer?.setVisible(on))
/**
 * 换选中测点要**重新同步热力层**：它现在只画选中那一个（见 heatmapLayer 的类注释），
 * 而 `displayPoints` 那条 watch 只在数据变化时触发——不补这一条，点了测点要等到下一次
 * 数据刷新（最长 60 秒）才看到晕圈跟着换。
 */
watch(
  () => popup.pointId,
  (pointId) => heatLayer?.sync(displayPoints.value, { visible: heatOn.value, selectedId: pointId }),
)
watch(sectorOn, (on) => mountainScene?.setSectorVisible(on))
watch(verticalOn, (on) => mountainScene?.setVerticalVisible(on))
watch(coverageOn, (on) => mountainScene?.setCoverageVisible(on))

// 主题切换要把 3D 场景底色一起换掉（影像与地形本身不动——那是数据，不是主题装饰）
watch(isDark, () => applyViewerTheme(viewer))

watch(
  () => store.projectId,
  (projectId, previous) => {
    if (viewer && projectId !== previous) loadProjectScene(projectId)
  },
)

/*
 * 换主测项：3D 的颜色/标签会自动跟着变（displayPoints 依赖它），
 * 但回放的帧是按旧测项拉的，得重新取一遍——否则时间轴上摆着 A 测项的数据，
 * 画面上却是 B 测项的标签。
 */
watch(
  () => store.primaryMetricCode,
  () => {
    if (replay.enabled) replay.load()
  },
)

onBeforeUnmount(() => {
  disposed = true
  sceneLoadGeneration += 1
  clearInterval(pollTimer)
  if (fitDebugTimer) clearInterval(fitDebugTimer)
  window.removeEventListener('resize', onWindowResize)
  clearTimeout(bannerTimer)
  // 回放的定时器不在组件里，但得跟着页面停，否则它会一直推着索引走
  replay.dispose()
  // 渲染层的销毁与重建共用一套（P1-12）：卸载时漏掉某一项，旧监听会留在内存里继续吃帧
  teardownViewer()
})
</script>

<template>
  <div class="screen">
    <div ref="container" class="globe" />
    <!-- 取景诊断（临时，2026-09-21）：模型半径 / 取景距离 / 相机离地高度，用来定位取景问题 -->
    <div v-if="fitDebug" class="hud fit-debug">{{ fitDebug }}</div>

    <section v-if="viewerError" class="hud viewer-error" role="alert">
      <h2>三维视图未能正常运行</h2>
      <p>{{ viewerError }}</p>
      <p>可重新加载页面；若仍失败，请保留浏览器控制台中的第一条错误。</p>
      <button class="btn" @click="reloadViewer">重新加载</button>
      <button class="btn" @click="router.push('/home')">返回工作台</button>
    </section>

    <!-- 顶栏 -->
    <header class="hud topbar">
      <div class="topbar-left">
        <span class="logo">CQXL</span>
        <span class="title">三维数字孪生监测大屏</span>
        <span class="project">{{ store.currentProject?.name || '—' }}</span>
        <!-- 试验标记：受光渲染 / 临时资产。避免把试验画面当成正式效果看走了眼。 -->
        <span v-if="LIT_RENDERING || ASSET_OVERRIDE" class="render-flag">
          试验渲染{{ LIT_RENDERING ? '·受光' : '' }}{{ ASSET_OVERRIDE ? '·资产覆盖' : '' }}
        </span>
        <!-- 项目切换：只影响读这份 store 的页面（就是本屏）——其余页面由后端按成员项目限范围 -->
        <label v-if="store.projects.length > 1" class="metric-pick">
          <span>项目</span>
          <el-select
            :model-value="store.projectId"
            size="small"
            style="width: 170px"
            @update:model-value="store.setProject"
          >
            <el-option
              v-for="p in store.projects"
              :key="p.id"
              :label="p.name"
              :value="p.id"
            />
          </el-select>
        </label>
        <!-- 主测项：3D 着色、热力图、时间轴回放都跟着它走（可选项来自测项档案） -->
        <label class="metric-pick">
          <span>主测项</span>
          <el-select
            :model-value="store.primaryMetricCode"
            size="small"
            style="width: 132px"
            @update:model-value="store.setPrimaryMetric"
          >
            <el-option
              v-for="code in store.metricCodes"
              :key="code"
              :label="store.metricMeta(code).name"
              :value="code"
            />
          </el-select>
        </label>
      </div>
      <div class="topbar-right">
        <span class="chip" :class="store.error ? 'err' : store.dataPointCount ? 'ok' : 'warn'">{{ dataStatus }}</span>
        <span class="chip" :class="realtime.isLive ? 'ok' : 'dim'">{{ realtime.statusText }}</span>
        <span
          class="chip"
          :class="mountainState === 'ok' ? 'ok' : mountainState === 'failed' ? 'err' : 'dim'"
          :title="sceneError || sceneDescription"
        >{{ MOUNTAIN_TEXT[mountainState] }}</span>
        <!-- 资产哈希核对结果（P0-5）：模型与库里登记的那一份是不是同一个。
             此前只有"加载成功/失败"这一档——加载成功、内容却已被替换，界面上一个字都不会说。 -->
        <span class="chip" :class="assetChipClass" :title="assetChipTitle">资产 {{ assetChipText }}</span>
        <span v-if="!LOCAL_SCENE_ENABLED" class="chip" :class="terrainState === 'ok' ? 'ok' : 'dim'">{{ TERRAIN_TEXT[terrainState] }}</span>
        <span class="chip" :class="['ion', 'offline'].includes(imageryState) ? 'ok' : 'dim'">{{ IMAGERY_TEXT[imageryState] }}</span>
        <!-- 没配 token → 明说，别让人对着「无地形/兜底底图」猜自己少了什么 -->
        <span v-if="!LOCAL_SCENE_ENABLED && !ION_CONFIGURED" class="chip err" :title="TOKEN_HINT">未配 ion token</span>
        <span class="chip dim">更新于 {{ fromNow(store.loadedAt) }}</span>
        <!-- 白天/黑夜：大屏也得跟着切，否则从工作台点进来会像换了个系统 -->
        <ThemeSwitch variant="hud" />
        <button class="btn" @click="refresh">刷新</button>
        <!-- 目标是 /home（总览），不是 /overview——后者没有注册路由，
             点下去会落进 catch-all 的 NotFoundView -->
        <button class="btn" @click="router.push('/home')">退出大屏</button>
      </div>
    </header>

    <!--
      当前项目没配场景：大屏这时只剩底色，和「加载失败」长得像，但成因完全不同。
      把区别说清楚，并给一个「切到已配置场景的项目」的按钮——不然用户只能自己猜。
    -->
    <section v-if="noScene" class="hud scene-warn" role="status">
      <span class="warn-title">「{{ store.currentProject?.name || '当前项目' }}」未配置数字孪生场景</span>
      <span class="warn-desc">
        这不是加载失败——该项目还没有三维资产，所以只显示离线底色（测点与曲线照常可用）。
      </span>
      <button
        v-if="sceneAlternate"
        class="btn"
        @click="store.setProject(sceneAlternate.id)"
      >
        切到「{{ sceneAlternate.name }}」
      </button>
      <span v-else class="warn-desc">可在上方「项目」里切换其它项目。</span>
    </section>

    <!--
      资产哈希不符（P0-5）：与"未配场景"分开说，因为这是**两条完全不同的处置建议**——
      前者去管理端配资产，后者要么是资产被换过，要么是库里的登记值过期了。
      展示上刻意不自动隐藏：它是"这台机器上看到的模型与平台记录不是同一份"这一事实，
      不是一次可以刷过去的事件。
    -->
    <section v-if="assetProblem" class="hud asset-warn" role="alert">
      <span class="warn-title">{{ HASH_TEXT[assetProblem.status] }}</span>
      <span class="warn-desc">
        资产 {{ assetProblem.assetUrl }}
        <template v-if="assetProblem.expected"> · 登记 {{ assetProblem.expected.slice(0, 16) }}…</template>
        <template v-if="assetProblem.actual"> · 实测 {{ assetProblem.actual.slice(0, 16) }}…</template>
        <template v-if="assetProblem.error"> · {{ assetProblem.error }}</template>
        —— 请先确认盘上的模型文件与数据库记录的 asset_sha256 哪一个是权威值。
      </span>
    </section>

    <!--
      WebGL 上下文丢失（P1-12）：画面**冻在最后一帧**，不提示的话看起来完全正常——
      这是这一条最危险的地方：值班员会继续看一份不再更新的画面。
      恢复后不自动重建，而是等人点：重建会短暂黑屏，且失败时要能看见原因。
    -->
    <section v-if="glLost" class="hud gl-warn" role="alert">
      <span class="warn-title">
        {{ glRestored ? 'WebGL 上下文已恢复，建议重建视图' : 'WebGL 上下文丢失：画面已冻结' }}
      </span>
      <span class="warn-desc">
        常见于显卡驱动重启或系统休眠唤醒。测点数据与实时推送不受影响，只是三维画面停止刷新。
      </span>
      <button class="btn" @click="rebuildViewer">重建三维视图</button>
      <button class="btn" @click="reloadViewer">重新加载页面</button>
    </section>

    <!-- 刚推来的告警：一闪而过的横幅，看一眼就知道「哪个点在报」 -->
    <transition name="banner">
      <div
        v-if="alarmBanner"
        class="hud alarm-banner"
        :style="{ borderColor: bannerColor(alarmBanner) }"
      >
        <span class="lv" :style="{ background: bannerColor(alarmBanner) }">{{ bannerLevelText(alarmBanner) }}</span>
        <span class="who">{{ bannerTarget(alarmBanner) }}</span>
        <span class="when">{{ formatTime(alarmBanner.triggeredAt) }}</span>
      </div>
    </transition>

    <!-- 左侧测点列表 -->
    <aside class="hud side">
      <!--
        分区导航（2026-09-23 用户需求）：全局视角先选分区 → 相机聚焦 + 只看该分区的测点。
        3D 里点分区椭圆也能触发同一个 selectZone（两条入口，行为一致）。
      -->
      <template v-if="zonesWithGeo.length">
        <div class="panel-title">分区（{{ zonesWithGeo.length }}）</div>
        <ul class="zone-list">
          <li
            v-for="zone in zonesWithGeo"
            :key="zone.id"
            :class="{ active: activeZone?.id === zone.id }"
            @click="selectZone(zone)"
          >
            <span class="code">{{ zone.name }}</span>
            <span class="value">{{ zonePointCount(zone) }} 点</span>
          </li>
          <li :class="{ active: !activeZone }" @click="resetView()">
            <span class="code">全区（不筛选）</span>
            <span class="value">{{ points.length }} 点</span>
          </li>
        </ul>
      </template>
      <div v-if="activeZone" class="zone-hint">正在看：{{ activeZone.name }} · {{ activeZone.desc }}</div>
      <div class="panel-title">测点（{{ displayPoints.length }}）</div>
      <input v-model="pointSearch" class="side-search" placeholder="搜索点号或名称" />
      <ul class="point-list">
        <li
          v-for="p in sidePoints"
          :key="p.id"
          :class="{ active: popup.pointId === p.id }"
          @click="focusPoint(p)"
        >
          <span class="dot" :style="{ background: resolvePointVisual(p).color }" />
          <span class="code">{{ p.code }}</span>
          <span class="value">{{ p.hasData ? `${formatSigned(p.value, 2)}${p.unit || ''}` : '—' }}</span>
        </li>
      </ul>
      <div v-if="sidePoints.length < displayPoints.length && !pointSearch" class="list-hint">
        列表仅显示前 {{ SIDE_LIST_LIMIT }} 个，搜索可定位其余测点
      </div>
      <div class="panel-foot">
        <button class="btn wide" @click="resetView">回到全局视角</button>
      </div>
    </aside>

    <!--
      雷达与视场（P1-11）。两件事必须分开说，否则会被读成一件：
        · 扇形 = **理论视场**：按量程/角度解析算出来的，不按地形裁剪；
        · 连线 = **这座雷达看到的每个目标的核验状态**（已核验 / 待核验 / 被遮挡）。
      选中一台才显示它的连线（规模场景上千条会糊住画面），覆盖面则两台都留。
    -->
    <!--
      右侧竖栏（2026-09-20 用户要求）：雷达面板与图例原来一个在右上、一个在底部居中，
      图例横铺一行、内容一多就压到时间轴上。现在两块都收进这个竖栏里：
      雷达在上、图例在下，图例内部也改成**竖排**，右侧留白被用起来、底部那条时间轴不再被压。
      空白处 pointer-events: none，不影响在 3D 场景里拖动/拾取。
    -->
    <div class="right-rail">
    <aside v-if="radars.length" class="hud radar-side">
      <div class="panel-title">雷达与视场（{{ radars.length }}）</div>
      <button
        v-for="radar in radars"
        :key="radar.deviceId"
        class="radar-row"
        :class="{ active: String(activeRadarId) === String(radar.deviceId) }"
        @click="selectRadar(radar.deviceId)"
      >
        <span class="radar-status" :class="radar.status === 'ONLINE' ? 'online' : 'offline'" />
        <span>
          <strong>{{ radar.name || radar.code }}</strong>
          <small>{{ radar.targets?.length || 0 }} 个已绑定目标</small>
        </span>
      </button>
      <div v-if="activeRadar" class="radar-meta">
        <span>量程 {{ formatNumber(activeRadar.detectionRangeM, 0) }}m</span>
        <span>水平 ±{{ formatNumber(activeRadar.halfAngleDegrees, 0) }}°</span>
        <span>垂直 ±{{ formatNumber(activeRadar.verticalHalfAngleDegrees, 0) }}°</span>
      </div>
      <div v-if="activeRadar" class="radar-verify">
        <span class="vk verified">已核验 {{ radarTargetStats.verified }}</span>
        <span class="vk pending">待核验 {{ radarTargetStats.pending }}</span>
        <span class="vk blocked">被遮挡 {{ radarTargetStats.blocked }}</span>
      </div>
      <div v-if="activeRadar" class="radar-note">
        实心覆盖区是<strong>按地形裁剪</strong>的结果（沿每条方位线从雷达脚下往外，地面落到视线之下即止，
        外缘跟着山脊线走，与标定校核同一套判据、同一份高程场）；虚线扇面是<strong>理论视场</strong>
        （按量程与角度解析算出，不打地形）。两者都在图例里可单独关闭；垂直上下边界只显示选中的这一台；
        连线才是逐目标的核验结果。
      </div>
    </aside>

    <!--
      图例。位置必须**高于底部时间轴**——两者原来都锚在 bottom:16px，
      时间轴（left/right 都是 16px 的整条）把图例整个盖住了：
      连「地面热力图」那个开关都点不到（2026-09-17 用户报的就是这个遮挡）。
    -->
    <div class="hud legend">
      <div class="panel-title">图例</div>
      <div v-for="item in LEGEND" :key="item.key" class="legend-row">
        <span class="dot" :style="{ background: item.color }" />{{ item.label }}
      </div>
      <span class="legend-note">立柱高度为示意，非实测量值</span>
      <!--
        地面热力层的刻度（2026-09-20）：颜色 = 测值大小（发散色标，负冷正暖），
        半径 = 相对大小的视觉强调——半径没有物理含义，必须写出来，否则会被读成"影响半径"。
      -->
      <div v-if="heatOn" class="heat-legend">
        <div class="heat-legend-bar" :style="{ background: heatLegendGradient }" />
        <div class="heat-legend-scale">
          <span>{{ formatSigned(-heatMax, 2) }} {{ heatUnit }}</span>
          <span>0</span>
          <span>{{ formatSigned(heatMax, 2) }} {{ heatUnit }}</span>
        </div>
        <span class="legend-note">
          颜色＝测值大小（刻度取当前最大绝对值）；晕圈大小只是相对大小，不是影响半径。
          只显示选中测点的晕圈（与垂直视场同口径），点列表或 3D 里的测点即可切换。
        </span>
      </div>
      <span class="legend-note">场景：{{ sceneDescription }}</span>
      <!-- 主测项不只换数字：符号与立柱也跟着换，这里必须写出来，否则用户不知道「为什么变成空心环」 -->
      <span class="legend-note">
        主测项：{{ store.primaryMetric.name }} ——
        {{ isRateMetric ? '空心环 + 虚线立柱' : '实心圆 + 实线立柱' }}
      </span>
      <div class="layer-toggles">
        <label class="heat-toggle">
          <input v-model="heatOn" type="checkbox" />
          地面热力图（选中测点）
        </label>
        <label class="heat-toggle">
          <input v-model="coverageOn" type="checkbox" />
          地形裁剪覆盖（实测）
        </label>
        <label class="heat-toggle">
          <input v-model="sectorOn" type="checkbox" />
          雷达理论视场（虚线）
        </label>
        <label class="heat-toggle">
          <input v-model="verticalOn" type="checkbox" />
          垂直视场边界
        </label>
      </div>
    </div>

    <!-- 时间轴回放（2026-09-21 用户要求：从底部挪进右侧竖栏，与雷达面板/图例同列） -->
    <div class="hud timeline">
      <button class="btn" :class="{ primary: replay.enabled }" @click="replay.toggle()">
        {{ replay.enabled ? '退回实时' : '时间轴回放' }}
      </button>

      <template v-if="replay.enabled">
        <button class="btn" :disabled="replay.loading || !replay.total" @click="replay.playing ? replay.pause() : replay.play()">
          {{ replay.playing ? '⏸ 暂停' : '▶ 播放' }}
        </button>
        <!--
          回放窗口可选。以前是 7 天硬编码——想看今天上午那一段，也只能把 7 天全拉下来。
          `setRange` 自己在「正在回放」时重拉，这里不用再补一次 load。
        -->
        <el-select
          :model-value="replay.rangeHours"
          size="small"
          style="width: 118px"
          :disabled="replay.loading"
          @update:model-value="replay.setRange"
        >
          <el-option v-for="r in REPLAY_RANGES" :key="r.value" :label="r.label" :value="r.value" />
        </el-select>
        <el-slider
          v-model="replayProgress"
          :min="0"
          :max="1000"
          :step="1"
          :show-tooltip="false"
          :disabled="!replay.total"
          class="timeline-slider"
        />
        <span class="stamp mk-mono">{{ formatTime(replay.time) }}</span>
        <span class="dim-text">{{ replay.index + 1 }} / {{ replay.total }}</span>
        <span class="dim-text">{{ store.metricMeta(replay.metricCode).name }}</span>
        <!--
          粒度必须说出来（第 18 条）：降采样之后「近 7 天」只剩 168 个点，
          不标注的话用户的第一反应是「数据丢了吧」
        -->
        <span class="dim-text">{{ replayGranularityText }}</span>
        <span class="dim-text">{{ replayFrameInfo }}</span>
        <span v-if="replay.error" class="err-text">{{ replay.error }}</span>
      </template>
      <span v-else class="dim-text">实时推送中 · 打开时间轴可回看历史形变</span>
    </div>
    </div>

    <!--
      点击测点后的浮窗。
      2026-09-23（用户反馈"测点数据太僵硬、点一下没有任何反馈"）：加了入场/切点/离场三套动效，
      而且**按 pointId 做 key**——点另一个点时整个浮窗重挂载，入场动画自然重放，
      数据行的错峰动画也跟着重来，肉眼能确认"这是刚点出来的另一个点"。
      位置仍然是每帧跟随测点投影，所以动画只用 opacity/transform，不动 left/top。
    -->
    <Transition name="popup-pop">
      <div
        v-if="popup.visible && selected"
        :key="popup.pointId"
        class="hud popup"
        :style="{ left: `${popup.x}px`, top: `${popup.y}px` }"
      >
      <div class="popup-head">
        <span class="code">{{ selected.code }}</span>
        <span class="name">{{ selected.name }}</span>
        <span class="dot" :style="{ background: resolvePointVisual(selected).color }" />
        <button class="close" @click="closePopup">×</button>
      </div>
      <div class="popup-body">
        <!-- 测项按档案列（当前主测项标出来），不再写死「累计形变 / 形变速率」 -->
        <div
          v-for="(row, index) in selectedMetricRows"
          :key="row.code"
          class="kv reveal"
          :class="{ main: row.primary }"
          :style="{ animationDelay: `${60 + index * 45}ms` }"
        >
          <span>{{ row.name }}<template v-if="row.primary">（主）</template></span>
          <b>
            <AnimatedNumber :value="Number.isFinite(row.value) ? Number(row.value) : null" :digits="3" />
            <em>{{ row.unit }}</em>
          </b>
        </div>
        <div class="kv">
          <span>场景</span>
          <b>{{ selected.sceneName }}</b>
        </div>
        <div class="kv">
          <span>状态</span>
          <b :style="{ color: resolvePointVisual(selected).color }">{{ resolvePointVisual(selected).label }}</b>
        </div>
        <div class="kv">
          <span>采集时间</span>
          <b>{{ formatTime(selected.collectTime) }}</b>
        </div>
        <div v-for="source in selectedRadarSources" :key="source.target.bindingId" class="source-card">
          <div class="kv">
            <span>来源雷达</span>
            <b>{{ source.radar.name || source.radar.code }}</b>
          </div>
          <div class="kv">
            <span>标定目标</span>
            <b>{{ source.target.targetCode || '待标定' }}</b>
          </div>
          <div class="kv">
            <span>斜距 / 视线</span>
            <b>
              {{ formatNumber(source.target.slantRangeM, 1) }}m ·
              {{ source.target.lineOfSight ? '无遮挡' : '被遮挡' }}
            </b>
          </div>
        </div>
        <!-- 最新一张现场影像（验收第 6 条要求「详情**与 3D 大屏**」都能看）。
             用 A 的 MediaGallery：缩略图只显示一张，点开可翻该点全部影像（支持删除）；
             该点没有影像时整块不出现（hide-empty），不留空档。 -->
        <MediaGallery
          :point-id="popup.pointId"
          :max="1"
          :columns="1"
          size="78px"
          hide-empty
          class="popup-media"
        />
      </div>
      <div class="popup-foot">
        <button class="btn" @click="router.push('/points')">去看曲线</button>
        <button class="btn" @click="router.push(`/media?pointId=${popup.pointId}`)">现场照片</button>
      </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.hud.viewer-error {
  position: absolute;
  z-index: 100;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: min(560px, calc(100% - 40px));
  padding: 24px;
  overflow-wrap: anywhere;
}
.viewer-error h2 { font-size: 18px; margin: 0 0 16px; }
.viewer-error p { line-height: 1.7; }
.viewer-error .btn { margin-right: 12px; }
.screen {
  position: relative;
  width: 100%;
  height: 100vh;
  overflow: hidden;
  background: var(--mk-screen-bg);
}

.globe {
  position: absolute;
  inset: 0;
}

/* HUD 都是浮在 3D 之上的独立面板，鼠标事件默认穿透，只有面板自己接收 */
.hud {
  position: absolute;
  z-index: 10;
  color: var(--mk-hud-text);
  background: var(--mk-hud-bg);
  border: 1px solid var(--mk-hud-border);
  border-radius: 8px;
  backdrop-filter: blur(6px);
}

.topbar {
  top: 0;
  right: 0;
  left: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  height: 52px;
  padding: 0 16px;
  border-radius: 0;
  border-width: 0 0 1px 0;
}

.topbar-left,
.topbar-right {
  display: flex;
  gap: 10px;
  align-items: center;
}

/* 顶栏的主测项选择：大屏自己的一条设置，不抢戏 */
.metric-pick {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-left: 6px;
  font-size: 12px;
  color: var(--mk-hud-muted);
}

/* 告警横幅：贴在顶栏下方居中，12s 后自己消失（见 showBanner 的定时器） */
.alarm-banner {
  top: 60px;
  left: 50%;
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 8px 14px;
  font-size: 13px;
  border-width: 1px;
  border-style: solid;
  transform: translateX(-50%);
}

/* 「本项目未配场景」：贴在左上角，不抢中央的视觉，也不能被忽略 */
.scene-warn {
  top: 62px;
  left: 16px;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  max-width: 520px;
  padding: 10px 14px;
  border-color: rgba(230, 162, 60, 0.5);
}

.scene-warn .warn-title {
  font-size: 13px;
  font-weight: 700;
  color: #f2c078;
}

.scene-warn .warn-desc {
  font-size: 12px;
  line-height: 1.5;
  color: var(--mk-hud-muted);
}

/*
  资产哈希不符（P0-5）：与「未配场景」同形（都是左上角一条），但颜色按"要处置"处理——
  左边框与标题用告警红，而正文沿用 HUD 的次级文字色，避免整块发亮。
  位置错开 62px 是因为两者理论上可能同时出现（场景加载成功、但哈希不符），
  叠在一起会互相盖住。
*/
.asset-warn {
  top: 112px;
  left: 16px;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  max-width: 560px;
  padding: 10px 14px;
  border-color: rgba(245, 108, 108, 0.55);
}

.asset-warn .warn-title {
  font-size: 13px;
  font-weight: 700;
  color: #f56c6c;
}

.asset-warn .warn-desc {
  font-size: 12px;
  line-height: 1.5;
  color: var(--mk-hud-muted);
  word-break: break-all;
}

/*
  WebGL 上下文丢失（P1-12）：与前两条错开位置（62 / 112 / 162），
  三条同时出现的概率极低，但叠在一起会互相盖住，代价只是多一个 top。
*/
.gl-warn {
  top: 162px;
  left: 16px;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  max-width: 560px;
  padding: 10px 14px;
  border-color: rgba(230, 162, 60, 0.55);
}

.gl-warn .warn-title {
  font-size: 13px;
  font-weight: 700;
  color: #f2c078;
}

.gl-warn .warn-desc {
  font-size: 12px;
  line-height: 1.5;
  color: var(--mk-hud-muted);
}

.alarm-banner .lv {
  padding: 1px 8px;
  font-size: 12px;
  font-weight: 700;
  color: #0b1622;
  border-radius: 9px;
}

.alarm-banner .who {
  font-weight: 700;
  font-family: Consolas, Menlo, monospace;
}

.alarm-banner .when {
  color: var(--mk-hud-muted);
}

.banner-enter-active,
.banner-leave-active {
  transition: opacity 0.35s ease, transform 0.35s ease;
}

.banner-enter-from,
.banner-leave-to {
  opacity: 0;
  transform: translate(-50%, -8px);
}

.logo {
  padding: 2px 7px;
  font-size: 12px;
  font-weight: 700;
  color: #06101f;
  background: linear-gradient(135deg, #6fd3ff, #35d0ba);
  border-radius: 4px;
}

.title {
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 0.04em;
}

.project {
  font-size: 13px;
  color: var(--mk-hud-muted);
}

.chip {
  padding: 3px 9px;
  font-size: 12px;
  border: 1px solid rgba(90, 170, 255, 0.28);
  border-radius: 999px;
}

.chip.ok {
  color: #7ef0d2;
  border-color: rgba(126, 240, 210, 0.45);
}

.chip.warn {
  color: #ffd479;
  border-color: rgba(255, 212, 121, 0.45);
}

.chip.dim {
  color: var(--mk-hud-muted);
}

/* 加载失败要用告警红：琥珀色「warn」在这块深色大屏上不够刺眼，
   而这一格恰恰是演示时「系统到底有没有在跑」的第一眼 */
.chip.err {
  color: #ff8a8a;
  border-color: rgba(255, 138, 138, 0.5);
}

.btn {
  padding: 4px 10px;
  font-size: 12px;
  color: var(--mk-hud-text);
  cursor: pointer;
  background: rgba(31, 111, 235, 0.25);
  border: 1px solid rgba(90, 170, 255, 0.4);
  border-radius: 5px;
}

.btn:hover {
  background: rgba(31, 111, 235, 0.42);
}

.btn.wide {
  width: 100%;
}

.side {
  top: 68px;
  bottom: 132px;
  left: 16px;
  display: flex;
  flex-direction: column;
  width: 226px;
  padding: 12px;
}

.right-rail {
  position: absolute;
  z-index: 10;
  top: 68px;
  right: 16px;
  /* 让开底部时间轴（它 left/right 都是 16px、高约 52px + 16px 边距） */
  bottom: 84px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  align-items: stretch;
  width: 296px;
  /* 内容多时自己滚，不顶出屏幕；空白处不挡 3D 交互 */
  overflow-y: auto;
  pointer-events: none;
}

.right-rail > * {
  pointer-events: auto;
}

.radar-side {
  /* 从"绝对定位在右上角"改成竖栏里的普通块（位置由 .right-rail 决定） */
  position: static;
  width: auto;
  padding: 12px;
}

.radar-row {
  display: flex;
  gap: 9px;
  align-items: center;
  width: 100%;
  padding: 8px;
  margin-bottom: 6px;
  color: var(--mk-hud-text);
  text-align: left;
  cursor: pointer;
  background: rgba(3, 13, 27, 0.56);
  border: 1px solid rgba(90, 170, 255, 0.18);
  border-radius: 6px;
}

.radar-row.active {
  background: rgba(31, 111, 235, 0.28);
  border-color: rgba(85, 230, 165, 0.55);
}

.radar-row strong,
.radar-row small {
  display: block;
}

.radar-row strong {
  font-size: 12px;
}

.radar-row small {
  margin-top: 2px;
  font-size: 10px;
  color: var(--mk-hud-muted);
}

.radar-status {
  width: 9px;
  height: 9px;
  border-radius: 50%;
}

.radar-status.online {
  background: #55e6a5;
  box-shadow: 0 0 8px rgba(85, 230, 165, 0.75);
}

.radar-status.offline {
  background: #8a94a6;
}

.radar-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
  padding-top: 5px;
  font-size: 10px;
  color: var(--mk-hud-muted);
}

/* 三档核验统计（P1-11）：颜色与目标连线一一对应，扫一眼就知道"绑定数"里有多少能采信 */
.radar-verify {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 8px;
  padding-top: 6px;
  font-size: 10px;
}

.radar-verify .vk::before {
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-right: 4px;
  vertical-align: middle;
  content: '';
  border-radius: 50%;
}

.radar-verify .verified {
  color: #55e6a5;
}

.radar-verify .verified::before {
  background: #55e6a5;
}

.radar-verify .pending {
  color: #f4c95d;
}

.radar-verify .pending::before {
  background: #f4c95d;
}

.radar-verify .blocked {
  color: #ff6b6b;
}

.radar-verify .blocked::before {
  background: #ff6b6b;
}

/* 口径说明：这一段不是装饰——"理论视场"与"已核验"的差别说不清就会变成误解 */
.radar-note {
  padding-top: 6px;
  font-size: 10px;
  line-height: 1.5;
  color: var(--mk-hud-muted);
}

/* 热力色标：一条渐变条 + 两端刻度。放在图例里，和"哪个颜色代表什么"挨着 */
.heat-legend {
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
  margin-top: 4px;
}

.heat-legend-bar {
  height: 8px;
  border: 1px solid var(--mk-border);
  border-radius: 3px;
}

.heat-legend-scale {
  display: flex;
  justify-content: space-between;
  font-size: 10px;
  color: var(--mk-hud-muted);
}

.panel-title {
  margin-bottom: 10px;
  font-size: 12px;
  letter-spacing: 0.1em;
  color: var(--mk-hud-muted);
}

.side-search {
  box-sizing: border-box;
  width: 100%;
  padding: 7px 9px;
  margin-bottom: 8px;
  color: var(--mk-hud-text);
  outline: none;
  background: rgba(3, 13, 27, 0.75);
  border: 1px solid rgba(90, 170, 255, 0.25);
  border-radius: 5px;
}

.side-search:focus {
  border-color: rgba(90, 170, 255, 0.65);
}

.list-hint {
  padding-top: 6px;
  font-size: 11px;
  line-height: 1.4;
  color: var(--mk-hud-muted);
}

.point-list {
  flex: 1;
  padding: 0;
  margin: 0;
  overflow-y: auto;
  list-style: none;
}

.point-list li {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 7px 8px;
  margin-bottom: 4px;
  font-size: 13px;
  cursor: pointer;
  border: 1px solid transparent;
  border-radius: 5px;
}

.point-list li:hover {
  background: rgba(31, 111, 235, 0.18);
}

.point-list li.active {
  background: rgba(31, 111, 235, 0.28);
  border-color: rgba(90, 170, 255, 0.5);
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  box-shadow: 0 0 8px currentColor;
}

.code {
  font-family: Consolas, Monaco, monospace;
  font-weight: 600;
}

.point-list .value {
  margin-left: auto;
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  color: var(--mk-hud-muted);
}

/* 分区导航（2026-09-23）：点分区 → 聚焦 + 过滤测点 */
.zone-list {
  list-style: none;
  margin: 0 0 6px;
  padding: 0;
}

.zone-list li {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 3px 6px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
  color: var(--mk-hud-muted);
}

.zone-list li:hover {
  background: rgba(78, 168, 255, 0.14);
}

.zone-list li.active {
  background: rgba(255, 180, 84, 0.18);
  color: var(--mk-hud-text);
}

.zone-list li .value {
  margin-left: auto;
  font-family: Consolas, Monaco, monospace;
  font-size: 11px;
  opacity: 0.75;
}

.zone-hint {
  margin: 4px 0 6px;
  font-size: 11px;
  line-height: 1.5;
  color: var(--mk-hud-muted);
}

.panel-foot {
  padding-top: 10px;
}

.legend {
  /* 2026-09-20：从"底部居中横排"改成"右侧竖栏里的一块竖排面板"（用户要求）。
     位置/宽度交给 .right-rail，这里只负责内部排版。 */
  position: static;
  display: flex;
  flex-direction: column;
  gap: 5px;
  align-items: flex-start;
  padding: 8px 16px;
  /* 2026-09-22（用户要求）：图例面板再透明一点，和地图背景融合。
     深色主题下从 rgba(6,16,31,0.74) 降到 0.45，浅色主题下从 0.88 降到 0.72；
     文字加一点阴影，压在半透明的底上仍然读得清。 */
  background: rgba(6, 16, 31, 0.45);
  backdrop-filter: blur(8px);
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.55);
  /* 注意：原来这里还有 transform: translateX(-50%)（配合 left:50% 居中）。
     改成静态定位后它不会消失——left 失效但 transform 照旧生效，
     于是整块面板被左移半个宽度、内容在竖栏左边被裁掉（实测偏了 148px）。
     居中改竖排时**两处都要清**：left/right 与 transform。 */
  transform: none;
}

:root[data-theme='light'] .legend {
  background: rgba(255, 255, 255, 0.72);
  text-shadow: 0 1px 2px rgba(255, 255, 255, 0.7);
}

.legend .dot {
  /* 图例上的小圆点也跟着淡一点：原来是纯色 + 8px 发光，压在影像上很跳 */
  opacity: 0.82;
  box-shadow: 0 0 6px currentColor;
}

/* 图例是竖排的，行间距收紧一点，别把大屏占掉半屏 */
.legend .legend-row,
.legend .legend-note {
  margin: 0;
}

.layer-toggles {
  /* 竖栏里横排三个复选框会挤成两行，改成竖排——本来右侧就是"一条一条读"的排版 */
  display: flex;
  flex-direction: column;
  gap: 2px;
  align-items: flex-start;
  margin-top: 2px;
}

.legend .panel-title {
  margin: 0;
}

.legend-row {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-top: 0;
  font-size: 12px;
  color: var(--mk-hud-muted);
}

.legend-note {
  padding-top: 4px;
  font-size: 11px;
  line-height: 1.5;
  color: var(--mk-hud-muted);
  border-top: 1px solid rgba(90, 170, 255, 0.18);
}

/* 浮窗以测点位置为锚点，向左上偏移，避免挡住标点本身 */
.popup {
  width: 264px;
  padding: 10px 12px;
  transform: translate(-50%, calc(-100% - 18px));
  /* 入场/切点/离场动效（2026-09-23）。只动 opacity 与 transform：
     位置是每帧由 trackPopup() 写 left/top 的，动画碰 left/top 会打架。 */
  transition: opacity 0.16s ease, transform 0.18s cubic-bezier(0.2, 0.9, 0.3, 1.2);
  will-change: opacity, transform;
}

.popup-pop-enter-from,
.popup-pop-leave-to {
  /* 从"更靠下、略小"处冒出来：像从测点上弹出来的，而不是凭空切出来 */
  opacity: 0;
  transform: translate(-50%, calc(-100% - 6px)) scale(0.92);
}

.popup-pop-enter-to,
.popup-pop-leave-from {
  opacity: 1;
  transform: translate(-50%, calc(-100% - 18px)) scale(1);
}

/* 数据行错峰入场：点一个新点时，先读到点号与主测项，再一行行"落"下来 */
.popup .reveal {
  animation: popup-row-in 0.34s cubic-bezier(0.2, 0.8, 0.3, 1) both;
}

@keyframes popup-row-in {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* 系统开了"减少动态效果"就全部摊平：动画是锦上添花，不能变成眩晕源 */
@media (prefers-reduced-motion: reduce) {
  .popup,
  .popup .reveal {
    transition: none;
    animation: none;
  }
}

.popup-head {
  display: flex;
  gap: 8px;
  align-items: center;
  padding-bottom: 8px;
  border-bottom: 1px solid rgba(90, 170, 255, 0.18);
}

.popup-head .name {
  font-size: 12px;
  color: var(--mk-hud-muted);
}

.close {
  margin-left: auto;
  font-size: 16px;
  line-height: 1;
  color: var(--mk-hud-muted);
  cursor: pointer;
  background: none;
  border: none;
}

.popup-body {
  padding: 8px 0;
}

.kv {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 3px 0;
  font-size: 12px;
  color: var(--mk-hud-muted);
}

.kv b {
  font-family: Consolas, Monaco, monospace;
  font-size: 13px;
  font-weight: 600;
  color: var(--mk-hud-text);
}

.kv em {
  font-size: 11px;
  font-style: normal;
  font-weight: 400;
  color: var(--mk-hud-muted);
}

/* 主测项那一行加粗一点：弹窗里好几行测项，得一眼看出画面上的颜色是按哪个来的 */
.kv.main b {
  color: #7fc4ff;
}

.source-card {
  padding: 5px 7px;
  margin-top: 6px;
  background: rgba(85, 230, 165, 0.06);
  border: 1px solid rgba(85, 230, 165, 0.18);
  border-radius: 5px;
}

.kv.photos {
  align-items: flex-start;
  margin-top: 2px;
}

/*
 * MediaGallery 是给亮色页面写的（--mk-border / --mk-bg），浮窗是深色的，
 * 所以这里把缩略图的边框/底色换成 HUD 的蓝线，否则亮色描边浮在深色玻璃上很突兀。
 * :deep() 必要：画廊的样式是 scoped 的，从外面够不着。
 */
.popup-media :deep(.grid) {
  padding: 8px 0 4px;
}

.popup-media :deep(.thumb) {
  border-color: rgba(90, 170, 255, 0.28);
  background: rgba(255, 255, 255, 0.04);
  width: 78px;
}

.popup-media :deep(.time),
.popup-media :deep(.note) {
  color: var(--mk-hud-muted);
}

/* 地面热力图开关：图例下面的小复选，不该抢视觉 */
.heat-toggle {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-top: 6px;
  font-size: 11px;
  color: var(--mk-hud-muted);
  cursor: pointer;
}

/* 底部时间轴 */
.timeline {
  right: 16px;
  bottom: 16px;
  left: 16px;
  display: flex;
  gap: 12px;
  align-items: center;
  padding: 8px 14px;
}

.timeline-slider {
  flex: 1;
  min-width: 220px;
}

.timeline .stamp {
  font-size: 12px;
  color: var(--mk-hud-text);
  white-space: nowrap;
}

.timeline .dim-text,
.timeline .err-text {
  font-size: 11px;
  white-space: nowrap;
}

.timeline .dim-text {
  color: var(--mk-hud-muted);
}

.timeline .err-text {
  color: #ff8a8a;
}

.btn.primary {
  color: #06101f;
  background: #5aa9ff;
  border-color: #5aa9ff;
}

.popup-foot {
  display: flex;
  gap: 8px;
  padding-top: 8px;
  border-top: 1px solid rgba(90, 170, 255, 0.18);
}

.popup-foot .btn {
  width: 100%;
}

/* Cesium 自带版权署名容器的位置微调，避免压住左下角图例 */
:deep(.cesium-widget-credits) {
  font-size: 10px;
}

/* 试验标记（受光渲染 / 资产覆盖）：只在打开对应开关时出现 */
.render-flag {
  margin-left: 8px;
  padding: 1px 7px;
  border-radius: 999px;
  border: 1px solid rgba(255, 176, 87, 0.55);
  color: #ffb057;
  font-size: 11px;
  letter-spacing: 0.3px;
}

/*
 * 时间轴挪进右侧竖栏（2026-09-21）：它原来是底部整条（left/right:16px、横排），
 * 现在作为竖栏里的一块普通面板——位置交给 .right-rail，内部改成竖排。
 * 选择器带 .right-rail 前缀，特异性高于原来的 .timeline，覆盖旧的绝对定位。
 */
.right-rail .timeline {
  position: static;
  left: auto;
  right: auto;
  bottom: auto;
  top: auto;
  width: 100%;
  flex-direction: column;
  align-items: stretch;
  gap: 8px;
}

.right-rail .timeline .timeline-slider {
  width: 100%;
  margin: 0;
}

/* 取景诊断（临时）：左下角一行小字，定位完取景问题就删 */
.fit-debug {
  position: absolute;
  left: 16px;
  bottom: 16px;
  padding: 4px 10px;
  font-size: 12px;
  font-family: ui-monospace, Consolas, monospace;
  color: #9fe8ff;
  background: rgba(6, 16, 31, 0.72);
  border: 1px solid rgba(159, 232, 255, 0.35);
  border-radius: 6px;
  pointer-events: none;
}
</style>
