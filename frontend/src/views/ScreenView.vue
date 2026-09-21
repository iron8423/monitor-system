<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import {
  Cesium,
  ION_CONFIGURED,
  LOCAL_SCENE_ENABLED,
  applyViewerTheme,
  createViewer,
  flyToPoint,
  flyToPoints,
  setupImagery,
  setupTerrain,
} from '@/cesium/createViewer'
import { createDigitalTwinScene } from '@/cesium/digitalTwinScene'
import { ASSET_OVERRIDE, LIT_RENDERING } from '@/cesium/renderProfile'
import { createPointLayer } from '@/cesium/pointLayer'
import { createHeatmapLayer } from '@/cesium/heatmapLayer'
import { deviceCoverage, projectDigitalTwin } from '@/api/monitor'
import MediaGallery from '@/components/MediaGallery.vue'
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
let mountainScene = null
let clickHandler = null
let removePostRender = null
let pollTimer = null
let bannerTimer = null
let sceneLoadGeneration = 0
let disposed = false
let removeRenderError = null
const viewerError = ref('')

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
const assetChipText = computed(() => (assetCheck.value ? HASH_TEXT[assetCheck.value.status] || '资产核对' : '资产未核对'))
const assetChipTitle = computed(() => {
  const r = assetCheck.value
  if (!r) return '进入场景后自动核对模型 SHA-256'
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
  if (!replay.enabled) return points.value
  const values = replay.values
  return points.value.map((p) => {
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
/** 雷达视场扇面（每台雷达一个颜色，选中那台提亮）。与热力图一样属于「图层」，可单独关掉。 */
const sectorOn = ref(true)
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
const coverageOn = ref(true)

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
  if (mountainScene) mountainScene.flyHome()
  else if (viewer) flyToPoints(viewer, store.points)
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

/**
 * 按当前项目加载自己的数字孪生资产。generation 用于解决快速切换项目时的异步竞态：
 * 后发请求获胜，迟到的旧场景立即销毁，绝不覆盖新项目。
 */
async function loadProjectScene(projectId) {
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
    // 本地试验：把资产临时指到另一份 GLB（受光版），并且跳过哈希核对——
    // 数据库里记的是旧资产的 SHA-256，对着新资产核对只会得到一条假告警。
    // 真正上线要走新版本号迁移把 asset_sha256 一起更新。
    const overridden = Boolean(ASSET_OVERRIDE) && config?.enabled
    if (overridden) config.assetUrl = ASSET_OVERRIDE
    if (!config?.enabled) {
      mountainState.value = 'skipped'
      if (store.pointsOfProject.length) flyToPoints(viewer, store.pointsOfProject)
      // 顺手找一个配了场景的项目：这不是全库扫描，只是给「当前项目没配」的人一个能点的出口
      findSceneAlternate(projectId, generation)
      return null
    }
    // 资产哈希核对（P0-5）与模型加载并行：核对只是"报一条"，
    // 既不该拖慢首屏，也不该拦住场景——模型真坏了也要先把现场显示出来。
    if (!overridden) integrity.verify(config).catch(() => {})
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
    scene.flyHome({ duration: 1.8 })
    window.__digitalTwinScene = scene
    return scene
  } catch (error) {
    if (generation !== sceneLoadGeneration) return null
    mountainState.value = 'failed'
    sceneError.value = error?.message || String(error)
    console.error('[cesium] 数字孪生场景加载失败：', error)
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
    pointLayer = createPointLayer(viewer)
    heatLayer = createHeatmapLayer(viewer)
    // 暴露到全局，便于在浏览器控制台排查（也方便后续做演示调试）
    window.__viewer = viewer
    window.__Cesium = Cesium

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
  let tick = 0
  pollTimer = setInterval(() => {
    tick += 1
    if (!realtime.isLive) {
      store.refreshLatest()
    } else if (tick % 4 === 0) {
      store.refreshLatest()
    }
  }, 15000)
})

/*
 * 兜底轮询（3b：数据主路是 SSE，见 stores/realtime.js 与 stores/monitor.js）：
 *   - 流断了 → 15s 一次补齐，界面照常"活着"（宁可旧一点，也不能停在那一刻）；
 *   - 流正常 → 60s 才对一次账（每 4 跳一次），兜住推送覆盖不到的变化
 *     （新建测点、漏事件）。比后端产出周期刷得更快没有意义，只会白刷接口。
 */

/** 地形就绪后，把 7 根立柱的起点换到真实地形面上 */
async function plantMastsOnTerrain() {
  const list = store.points.filter(
    (p) => Number.isFinite(Number(p.longitude)) && Number.isFinite(Number(p.latitude)),
  )
  if (!list.length) return
  try {
    const cartos = list.map((p) => Cesium.Cartographic.fromDegrees(Number(p.longitude), Number(p.latitude)))
    const sampled = await Cesium.sampleTerrainMostDetailed(viewer.terrainProvider, cartos)
    pointLayer?.applyTerrainHeights(sampled.map((c, i) => [list[i].id, c.height]))
  } catch (error) {
    console.warn('[cesium] 地形高程采样失败，立柱沿用档案高程：', error?.message || error)
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
      <div class="panel-title">测点（{{ points.length }}）</div>
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

    <!-- 点击测点后的浮窗 -->
    <div
      v-if="popup.visible && selected"
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
        <div v-for="row in selectedMetricRows" :key="row.code" class="kv" :class="{ main: row.primary }">
          <span>{{ row.name }}<template v-if="row.primary">（主）</template></span>
          <b>
            {{ row.value === null || row.value === undefined ? '—' : formatNumber(row.value, 3) }}
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
  /* 注意：原来这里还有 transform: translateX(-50%)（配合 left:50% 居中）。
     改成静态定位后它不会消失——left 失效但 transform 照旧生效，
     于是整块面板被左移半个宽度、内容在竖栏左边被裁掉（实测偏了 148px）。
     居中改竖排时**两处都要清**：left/right 与 transform。 */
  transform: none;
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
</style>
