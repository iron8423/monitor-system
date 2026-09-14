<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import {
  Cesium,
  ION_CONFIGURED,
  createViewer,
  flyToPoint,
  flyToPoints,
  setupImagery,
  setupTerrain,
} from '@/cesium/createViewer'
import { createPointLayer } from '@/cesium/pointLayer'
import { createHeatmapLayer } from '@/cesium/heatmapLayer'
import MediaGallery from '@/components/MediaGallery.vue'
import { ALARM_LEVEL, resolvePointVisual } from '@/constants/status'
import { useMonitorStore } from '@/stores/monitor'
import { useRealtimeStore } from '@/stores/realtime'
import { useReplayStore } from '@/stores/replay'
import { formatNumber, formatSigned, formatTime, fromNow } from '@/utils/format'
import { frameProgress, indexFromProgress } from '@/utils/timeline'

defineOptions({ name: 'ScreenView' })

const router = useRouter()
const store = useMonitorStore()
const realtime = useRealtimeStore()
const replay = useReplayStore()
const userStore = useUserStore()

const container = ref(null)
let viewer = null
let pointLayer = null
let heatLayer = null
let clickHandler = null
let removePostRender = null
let pollTimer = null
let bannerTimer = null

/** 刚收到的告警横幅（12s 后自己消失） */
const alarmBanner = ref(null)

const terrainState = ref('loading') // loading | ok | failed | skipped
const imageryState = ref('loading') // loading | ion | fallback | failed

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
  fallback: '兜底底图',
  failed: '无底图',
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
      collectTime: replay.time || p.collectTime,
    }
  })
})

const selected = computed(() => displayPoints.value.find((p) => p.id === popup.pointId) || null)

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
  if (store.error) return '加载失败'
  if (replay.enabled) return `回放中 ${replay.index + 1}/${replay.total}`
  if (store.loading || !store.loadedAt) return '加载中…'
  return store.dataPointCount ? '正在报数' : '暂无数据'
})

/** 热力图开关（默认开：演示时「哪片区域形变大」最直观） */
const heatOn = ref(true)
/** 回放滑块用 0~1000 的整数步长，避免浮点抖动 */
const replayProgress = computed({
  get: () => frameProgress(replay.index, replay.total),
  set: (v) => replay.seek(indexFromProgress(v, replay.total)),
})
const replayFrameInfo = computed(() => {
  if (!replay.enabled) return ''
  return `${replay.currentHasData} 个测点有数据`
})

const LEGEND = [
  { key: 'normal', label: '正常', color: '#35b37e' },
  { key: 'over-threshold', label: '超限（≥3mm）', color: '#e6a23c' },
  { key: 'alarm', label: '告警中', color: '#f56c6c' },
  { key: 'suspect', label: '数据可疑', color: '#e6a23c' },
  { key: 'disappeared', label: '目标失联', color: '#8a94a6' },
  { key: 'no-data', label: '暂无数据', color: '#c0c4cc' },
]

function openPopup(pointId) {
  popup.pointId = pointId
  popup.visible = true
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
  if (viewer) flyToPoints(viewer, store.points)
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
  if (viewer && store.points.length) {
    flyToPoints(viewer, store.points, { duration: 1.8 })
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

onMounted(async () => {
  viewer = createViewer(container.value)
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
      closePopup()
    }
  }, Cesium.ScreenSpaceEventType.LEFT_CLICK)

  viewer.scene.postRender.addEventListener(trackPopup)
  removePostRender = () => viewer?.scene.postRender.removeEventListener(trackPopup)

  // 实时推送的连接归 store（它能覆盖到本页这个顶层路由），这里只负责唤醒
  realtime.start()

  // 先出数据、再升级地形/影像：任何一个环节慢，页面都已经有画面了
  await loadData()
  setupImagery(viewer).then((s) => (imageryState.value = s))
  setupTerrain(viewer).then(async (s) => {
    terrainState.value = s
    if (s === 'ok') {
      await plantMastsOnTerrain()
    }
  })

  /*
   * 3b：数据主路是 SSE（`stores/realtime.js` → `stores/monitor.js` 单一数据源），
   * 这里只留**兜底轮询**，两种情况：
   *   - 流断了 → 15s 一次补齐，界面照常"活着"（宁可旧一点，也不能停在那一刻）；
   *   - 流正常 → 60s 才对一次账（每 4 跳一次），兜住推送覆盖不到的变化
   *     （新建测点、漏事件）。比后端产出周期刷得更快没有意义，只会白刷接口。
   */
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
    heatLayer?.sync(list, { visible: heatOn.value })
  },
  { deep: true, immediate: true },
)

watch(heatOn, (on) => heatLayer?.setVisible(on))

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
  clearInterval(pollTimer)
  clearTimeout(bannerTimer)
  // 回放的定时器不在组件里，但得跟着页面停，否则它会一直推着索引走
  replay.dispose()
  removePostRender?.()
  clickHandler?.destroy()
  heatLayer?.destroy()
  pointLayer?.destroy()
  viewer?.destroy()
  viewer = null
})
</script>

<template>
  <div class="screen">
    <div ref="container" class="globe" />

    <!-- 顶栏 -->
    <header class="hud topbar">
      <div class="topbar-left">
        <span class="logo">UGMS</span>
        <span class="title">三维形变监测大屏</span>
        <span class="project">{{ store.currentProject?.name || '—' }}</span>
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
        <span class="chip" :class="terrainState === 'ok' ? 'ok' : 'dim'">{{ TERRAIN_TEXT[terrainState] }}</span>
        <span class="chip" :class="imageryState === 'ion' ? 'ok' : 'dim'">{{ IMAGERY_TEXT[imageryState] }}</span>
        <!-- 没配 token → 明说，别让人对着「无地形/兜底底图」猜自己少了什么 -->
        <span v-if="!ION_CONFIGURED" class="chip err" :title="TOKEN_HINT">未配 ion token</span>
        <span class="chip dim">更新于 {{ fromNow(store.loadedAt) }}</span>
        <button class="btn" @click="refresh">刷新</button>
        <!-- 目标是 /home（总览），不是 /overview——后者没有注册路由，
             点下去会落进 catch-all 的 NotFoundView -->
        <button class="btn" @click="router.push('/home')">退出大屏</button>
      </div>
    </header>

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
      <ul class="point-list">
        <li
          v-for="p in displayPoints"
          :key="p.id"
          :class="{ active: popup.pointId === p.id }"
          @click="focusPoint(p)"
        >
          <span class="dot" :style="{ background: resolvePointVisual(p).color }" />
          <span class="code">{{ p.code }}</span>
          <span class="value">{{ p.hasData ? `${formatSigned(p.value, 2)}${p.unit || ''}` : '—' }}</span>
        </li>
      </ul>
      <div class="panel-foot">
        <button class="btn wide" @click="resetView">回到全局视角</button>
      </div>
    </aside>

    <!-- 左下角图例 -->
    <div class="hud legend">
      <div class="panel-title">图例</div>
      <div v-for="item in LEGEND" :key="item.key" class="legend-row">
        <span class="dot" :style="{ background: item.color }" />{{ item.label }}
      </div>
      <span class="legend-note">立柱高度为示意，非实测量值</span>
      <span class="legend-note">热力图按主测项：{{ store.primaryMetric.name }}</span>
      <label class="heat-toggle">
        <input v-model="heatOn" type="checkbox" />
        地面热力图
      </label>
    </div>

    <!-- 底部时间轴：回放历史 / 退回实时 -->
    <div class="hud timeline">
      <button class="btn" :class="{ primary: replay.enabled }" @click="replay.toggle()">
        {{ replay.enabled ? '退回实时' : '时间轴回放' }}
      </button>

      <template v-if="replay.enabled">
        <button class="btn" :disabled="replay.loading || !replay.total" @click="replay.playing ? replay.pause() : replay.play()">
          {{ replay.playing ? '⏸ 暂停' : '▶ 播放' }}
        </button>
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
        <span class="dim-text">{{ replayFrameInfo }}</span>
        <span v-if="replay.error" class="err-text">{{ replay.error }}</span>
      </template>
      <span v-else class="dim-text">实时推送中 · 打开时间轴可回看历史形变</span>
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
.screen {
  position: relative;
  width: 100%;
  height: 100vh;
  overflow: hidden;
  background: #06101f;
}

.globe {
  position: absolute;
  inset: 0;
}

/* HUD 都是浮在 3D 之上的独立面板，鼠标事件默认穿透，只有面板自己接收 */
.hud {
  position: absolute;
  z-index: 10;
  color: #dbe7f5;
  background: rgba(6, 16, 31, 0.74);
  border: 1px solid rgba(90, 170, 255, 0.22);
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
  color: #8fa9c6;
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
  color: #8fa9c6;
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
  color: #8fa9c6;
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
  color: #8fa9c6;
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
  color: #cfe4ff;
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

.panel-title {
  margin-bottom: 10px;
  font-size: 12px;
  letter-spacing: 0.1em;
  color: #7fa6d0;
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
  color: #9fc2e8;
}

.panel-foot {
  padding-top: 10px;
}

.legend {
  bottom: 16px;
  left: 50%;
  display: flex;
  gap: 16px;
  align-items: center;
  padding: 8px 16px;
  transform: translateX(-50%);
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
  color: #b7cbe3;
}

.legend-note {
  padding-left: 8px;
  font-size: 11px;
  color: #7f93ad;
  border-left: 1px solid rgba(90, 170, 255, 0.25);
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
  color: #8fa9c6;
}

.close {
  margin-left: auto;
  font-size: 16px;
  line-height: 1;
  color: #8fa9c6;
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
  color: #8fa9c6;
}

.kv b {
  font-family: Consolas, Monaco, monospace;
  font-size: 13px;
  font-weight: 600;
  color: #dbe7f5;
}

.kv em {
  font-size: 11px;
  font-style: normal;
  font-weight: 400;
  color: #8fa9c6;
}

/* 主测项那一行加粗一点：弹窗里好几行测项，得一眼看出画面上的颜色是按哪个来的 */
.kv.main b {
  color: #7fc4ff;
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
  color: #8fa9c6;
}

/* 地面热力图开关：图例下面的小复选，不该抢视觉 */
.heat-toggle {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-top: 6px;
  font-size: 11px;
  color: #8fa9c6;
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
  color: #dbe7f5;
  white-space: nowrap;
}

.timeline .dim-text,
.timeline .err-text {
  font-size: 11px;
  white-space: nowrap;
}

.timeline .dim-text {
  color: #8fa9c6;
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
</style>
