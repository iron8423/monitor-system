<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import {
  Cesium,
  createViewer,
  flyToPoint,
  flyToPoints,
  setupImagery,
  setupTerrain,
} from '@/cesium/createViewer'
import { createPointLayer } from '@/cesium/pointLayer'
import { resolvePointVisual } from '@/constants/status'
import { useMonitorStore } from '@/stores/monitor'
import { formatNumber, formatSigned, formatTime, fromNow } from '@/utils/format'

defineOptions({ name: 'ScreenView' })

const router = useRouter()
const store = useMonitorStore()

const container = ref(null)
let viewer = null
let pointLayer = null
let clickHandler = null
let removePostRender = null
let pollTimer = null

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

const points = computed(() => store.enrichedPoints)
const selected = computed(() => points.value.find((p) => p.id === popup.pointId) || null)
// 首屏还没加载完时要显示「加载中」，不能先亮"暂无数据"（会让人以为系统坏了）
const dataStatus = computed(() => {
  if (store.loading || !store.loadedAt) return '加载中…'
  return store.dataPointCount ? '正在报数' : '暂无数据'
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

onMounted(async () => {
  viewer = createViewer(container.value)
  pointLayer = createPointLayer(viewer)
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

  // 先出数据、再升级地形/影像：任何一个环节慢，页面都已经有画面了
  await loadData()
  setupImagery(viewer).then((s) => (imageryState.value = s))
  setupTerrain(viewer).then(async (s) => {
    terrainState.value = s
    if (s === 'ok') {
      await plantMastsOnTerrain()
    }
  })

  // 阶段 3d 接 SSE 之前，先用轻量轮询让大屏"活着"（10s 一次，只刷最新值）
  pollTimer = setInterval(() => store.refreshLatest(), 10000)
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
  points,
  (list) => {
    pointLayer?.sync(list)
  },
  { deep: true, immediate: true },
)

onBeforeUnmount(() => {
  clearInterval(pollTimer)
  removePostRender?.()
  clickHandler?.destroy()
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
      </div>
      <div class="topbar-right">
        <span class="chip" :class="store.dataPointCount ? 'ok' : 'warn'">{{ dataStatus }}</span>
        <span class="chip" :class="terrainState === 'ok' ? 'ok' : 'dim'">{{ TERRAIN_TEXT[terrainState] }}</span>
        <span class="chip" :class="imageryState === 'ion' ? 'ok' : 'dim'">{{ IMAGERY_TEXT[imageryState] }}</span>
        <span class="chip dim">更新于 {{ fromNow(store.loadedAt) }}</span>
        <button class="btn" @click="loadData">刷新</button>
        <button class="btn" @click="router.push('/overview')">退出大屏</button>
      </div>
    </header>

    <!-- 左侧测点列表 -->
    <aside class="hud side">
      <div class="panel-title">测点（{{ points.length }}）</div>
      <ul class="point-list">
        <li
          v-for="p in points"
          :key="p.id"
          :class="{ active: popup.pointId === p.id }"
          @click="focusPoint(p)"
        >
          <span class="dot" :style="{ background: resolvePointVisual(p).color }" />
          <span class="code">{{ p.code }}</span>
          <span class="value">{{ p.hasData ? `${formatSigned(p.defoMm, 2)}mm` : '—' }}</span>
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
        <div class="kv">
          <span>累计形变</span>
          <b>{{ formatSigned(selected.defoMm, 4) }} <em>mm</em></b>
        </div>
        <div class="kv">
          <span>形变速率</span>
          <b>{{ formatNumber(selected.rateMmD, 4) }} <em>mm/d</em></b>
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
      </div>
      <div class="popup-foot">
        <button class="btn" @click="router.push('/points')">去看曲线</button>
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

.popup-foot {
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
