import * as Cesium from 'cesium'

import { resolvePointVisual } from '@/constants/status'
import { formatSigned } from '@/utils/format'

/** 测点上方立柱高度（米）：点形变雷达装在构筑物上，用一根立柱表示"测点在哪儿" */
// 引线高度（2026-09-21 用户要求"离地面更远"）：默认 60 m，可用 VITE_PIN_HEIGHT_M 覆盖。
const MAST_HEIGHT = parseFloat(import.meta.env.VITE_PIN_HEIGHT_M) || 60

/**
 * 生成"水滴 pin"贴图（billboard 用）。
 * 为什么用 canvas 画而不是放 PNG：颜色要跟状态走，7~20 种状态各一张图不如现画一张；
 * billboard 的宽高是**屏幕像素**，所以远近视距下大小恒定（用户要的"自适应缩放"），
 * billboard 又永远朝向相机（用户要的"自适应旋转"）。
 */
const pinCache = new Map()
function pinImage(cssColor) {
  if (pinCache.has(cssColor)) return pinCache.get(cssColor)
  const size = 64
  const canvas = document.createElement('canvas')
  canvas.width = canvas.height = size
  const ctx = canvas.getContext('2d')
  const cx = size / 2
  // 水滴：上方圆头 + 下方尖角（尖端就是锚点，配 verticalOrigin: BOTTOM）
  ctx.beginPath()
  ctx.arc(cx, size * 0.34, size * 0.26, Math.PI, 0, false)
  ctx.lineTo(cx, size * 0.94)
  ctx.closePath()
  ctx.fillStyle = cssColor
  ctx.fill()
  ctx.lineWidth = size * 0.06
  ctx.strokeStyle = 'rgba(6,16,31,0.85)'
  ctx.stroke()
  ctx.beginPath()
  ctx.arc(cx, size * 0.34, size * 0.09, 0, Math.PI * 2)
  ctx.fillStyle = 'rgba(255,255,255,0.92)'
  ctx.fill()
  const url = canvas.toDataURL('image/png')
  pinCache.set(cssColor, url)
  return url
}
/** 告警脉冲环的最大半径（米） */
const RING_MAX = 28

/**
 * Cesium 会分别读取椭圆的长、短半轴。若两个回调各自调用 Date.now()，
 * 在脉冲半径收缩时可能得到前后两个不同的值，瞬间形成“长轴 < 短轴”，
 * 从而触发 DeveloperError 并停止整帧渲染。
 *
 * 两个半轴必须复用同一个 Property，并以 Cesium 传入的同一帧时间计算。
 */
const pulseEpoch = Cesium.JulianDate.now()

function pulseProgress(time) {
  const seconds = time
    ? Cesium.JulianDate.secondsDifference(time, pulseEpoch)
    : 0
  return Math.sin(seconds / 0.42) * 0.5 + 0.5
}

/**
 * 测点图层：负责把"数据"翻译成"3D 实体"，并支持原地更新。
 *
 * 对外只暴露 sync(items) / highlight(pointId) 两个动作，
 * 数据从哪来（快照还是 SSE）它不关心 —— 这就是数据与视图解耦。
 *
 * 数据里跟测项有关的部分只有三样：`value`（当前主测项的值）、`unit`、`metricName`。
 * 它不认 `defo_mm` 这种具体测项代码——换一种测项，这一层一个字都不用改。
 */
export function createPointLayer(viewer, { labelDistance = 2000 } = {}) {
  /** pointId -> { mast, dot, ring, item } */
  const handles = new Map()
  /*
   * 水滴颜色（2026-09-21 用户要求"每个水滴颜色不同"）：pin 用**身份色**（这个点永远这个颜色，
   * 方便口头指认），地面锚点/立柱仍用**状态色**（绿=正常、橙=阈值、红=告警、灰=失联）。
   */
  const PIN_PALETTE = [
    '#4dd0e1', '#7bd389', '#ffd166', '#ff8fab', '#c792ea',
    '#8ecae6', '#f4a261', '#a3e635', '#f472b6', '#60a5fa',
  ]
  const pinColorOf = (item) => {
    const n = Number(item?.id)
    return PIN_PALETTE[(Number.isFinite(n) ? Math.abs(Math.trunc(n)) : 0) % PIN_PALETTE.length]
  }
  let removeHeightHandler = null

  /*
   * 空中 pin 高度自适应（2026-09-21 用户："缩小时水滴还是不够高"）：
   * 相机越高，引线越长——按相机离地高度取 10%，并夹在 [PIN_MIN, PIN_MAX] 之间，
   * 这样拉远时标签仍然"悬在空中"，不会缩成一排贴地的小点。
   * 用 postRender 节流到约 8 fps 更新，避免每帧重算 1000 个点。
   */
  const PIN_MIN_M = parseFloat(import.meta.env.VITE_PIN_HEIGHT_M) || 60
  const PIN_MAX_M = parseFloat(import.meta.env.VITE_PIN_HEIGHT_MAX_M) || 1500
  let lastHeightTick = 0
  removeHeightHandler = viewer.scene.postRender.addEventListener(() => {
    const now = Date.now()
    if (now - lastHeightTick < 120) return
    lastHeightTick = now
    const carto = Cesium.Cartographic.fromCartesian(viewer.camera.positionWC)
    const camHeight = carto ? carto.height : 0
    const h = Math.min(Math.max(camHeight * 0.1, PIN_MIN_M), PIN_MAX_M)
    for (const handle of handles.values()) {
      if (handle.lon === undefined) continue
      const top = Cesium.Cartesian3.fromDegrees(handle.lon, handle.lat, handle.baseHeight + h)
      handle.mast.polyline.positions = [handle.bottom, top]
      handle.dot.position = top
      if (handle.pin) handle.pin.position = top
    }
  })
  /** 正在闪的点（SSE 刚推来告警）：这些点即使没有未解除警情也要亮环 */
  const pulsing = new Set()

  /** 告警环按需创建。正常 1000 点不再常驻 1000 个隐藏椭圆和动态回调。 */
  function drawRing(item, ground) {
    const lon = Number(item.longitude)
    const lat = Number(item.latitude)
    const pulseRadius = new Cesium.CallbackProperty(
      (time) => 5 + pulseProgress(time) * RING_MAX * 0.42,
      false,
    )
    const ring = viewer.entities.add({
      id: `ring-${item.id}`,
      position: Cesium.Cartesian3.fromDegrees(lon, lat, ground + 1),
      ellipse: {
        semiMajorAxis: pulseRadius,
        semiMinorAxis: pulseRadius,
        material: new Cesium.ColorMaterialProperty(
          new Cesium.CallbackProperty((time) => {
            const current = handles.get(item.id)?.item || item
            const color = Cesium.Color.fromCssColorString(resolvePointVisual(current).color)
            return color.withAlpha(0.28 - pulseProgress(time) * 0.22)
          }, false),
        ),
        outline: true,
        outlineWidth: 1.5,
      },
      properties: { pointId: item.id },
    })
    ring.show = false
    return ring
  }

  function drawItem(item, stackIndex = 0) {
    const lon = Number(item.longitude)
    const lat = Number(item.latitude)
    const ground = Number(item.altitude) || 0
    if (!Number.isFinite(lon) || !Number.isFinite(lat)) return null

    const visual = resolvePointVisual(item)
    const color = Cesium.Color.fromCssColorString(visual.color)
    const pinColor = pinColorOf(item)
    const bottom = Cesium.Cartesian3.fromDegrees(lon, lat, ground)
    const top = Cesium.Cartesian3.fromDegrees(lon, lat, ground + MAST_HEIGHT)

    const mast = viewer.entities.add({
      id: `mast-${item.id}`,
      polyline: {
        positions: [bottom, top],
        width: 3,
        material: color.withAlpha(0.75),
      },
      properties: { pointId: item.id },
    })

    // 空中的"水滴 pin"：贴图颜色随状态变，尖端对准引线顶端；billboard 恒为屏幕像素大小，
    // 所以拉远拉近大小不变、永远正对相机（用户要的"自适应缩放和旋转"）。
    const pin = viewer.entities.add({
      id: `pin-${item.id}`,
      position: top,
      billboard: {
        image: pinImage(pinColor),
        width: 26,
        height: 26,
        verticalOrigin: Cesium.VerticalOrigin.BOTTOM,
        disableDepthTestDistance: Number.POSITIVE_INFINITY,
      },
      properties: { pointId: item.id },
    })

    const dot = viewer.entities.add({
      id: `point-${item.id}`,
      position: top,
      point: {
        // 2026-09-21 用户反馈"圆形图例不好看"：地面锚点收小，主视觉交给
        // "空中标签 + 引线连地面"（引线 = 上面的 mast，颜色按状态走）。
        pixelSize: 7,
        color,
        outlineColor: Cesium.Color.fromCssColorString('#06101f').withAlpha(0.9),
        outlineWidth: 1.5,
        disableDepthTestDistance: Number.POSITIVE_INFINITY,
      },
      // 标签文字在 sync() 里随数据一起更新
      label: {
        text: item.code,
        font: '13px "Microsoft YaHei", sans-serif',
        // 标签文字也按状态着色，远看能一眼分辨正常/告警（与图例一一对应）
        fillColor: color,
        showBackground: true,
        backgroundColor: Cesium.Color.fromCssColorString('#06101f').withAlpha(0.82),
        outlineColor: Cesium.Color.fromCssColorString('#06101f').withAlpha(0.9),
        outlineWidth: 2,
        backgroundPadding: new Cesium.Cartesian2(8, 4),
        // 同一场景内的测点往往只隔一两百米，标签会叠在一起 —— 按序号阶梯式上移错开
        pixelOffset: new Cesium.Cartesian2(0, -26 - stackIndex * 21),
        verticalOrigin: Cesium.VerticalOrigin.BOTTOM,
        horizontalOrigin: Cesium.HorizontalOrigin.CENTER,
        // 上限别设太紧：相机拉到全局视角时，到远端测点的斜距可能 8~10km，
        // 设 8000 会让边坡那组标签整片消失（曾经就是这样）
        distanceDisplayCondition: new Cesium.DistanceDisplayCondition(0, labelDistance),
        disableDepthTestDistance: Number.POSITIVE_INFINITY,
      },
      properties: { pointId: item.id, kind: 'point' },
    })

    return { item, mast, dot, pin, ring: null, color, baseHeight: ground, lon, lat, bottom }
  }

  /**
   * 用真实地形高度把立柱"种"到地面上。
   *
   * 为什么需要：档案里的 altitude（如 P-HK01 = 30m）和该点真实地形高度（实测约 7m）
   * 差着二十多米，直接用档案高程画，立柱会悬在半空、像根绳子挂着。
   * 地形采样成功后调用这里，把起点换到地形面。
   */
  function applyTerrainHeights(entries) {
    for (const [pointId, height] of entries) {
      const handle = handles.get(pointId)
      if (!handle || !Number.isFinite(height)) continue
      const lon = Number(handle.item.longitude)
      const lat = Number(handle.item.latitude)
      const bottom = Cesium.Cartesian3.fromDegrees(lon, lat, height)
      const top = Cesium.Cartesian3.fromDegrees(lon, lat, height + MAST_HEIGHT)
      handle.baseHeight = height
      handle.mast.polyline.positions = [bottom, top]
      handle.dot.position = new Cesium.ConstantPositionProperty(top)
      if (handle.ring) {
        handle.ring.position = new Cesium.ConstantPositionProperty(
          Cesium.Cartesian3.fromDegrees(lon, lat, height + 1),
        )
      }
    }
  }

  function paint(handle, item) {
    const visual = resolvePointVisual(item)
    const color = Cesium.Color.fromCssColorString(visual.color)
    /*
     * 主测项不同 → 符号不同（2026-09-17 用户提出：累积形变与形变速率「看不出区别」）。
     *
     * 这两个测项语义完全不同：**累积形变是状态量**（一共变形了多少），
     * **形变速率是趋势量**（还在不在变形）。此前两者都画成实心圆 + 实线立柱，
     * 只有数字和单位不同——而毫米级的数字长得几乎一样。
     *
     * 编码：
     *   · 累积形变 → 实心圆 + 实线立柱（现状）
     *   · 形变速率 → 空心环 + 虚线立柱
     * 单位也照旧跟在数值后面（`item.unit`），所以「空心环 + mm/d」是同一件事的两种提示。
     * 判据用 metricCode 而不是单位：单位是档案里的字符串，改档不该改渲染规则。
     */
    const isRate = String(item.metricCode || '').includes('rate')
    // 数据过期（清单第 19 条）：**值照常显示**，只加一个后缀、把字调暗。
    // 只说「过期」不显示数，值班的人还是不知道最后量到的是多少——那正是他要的信息。
    // 判定不在这里做（`stale` 由 buildFrames 算好、经 displayPoints 传进来，
    // 在这里再比一次时间就会出现「同一个值两个颜色」）
    const stale = visual.key === 'stale'
    const value = item.hasData
      ? `${formatSigned(item.value, 2)}${item.unit || ''}${stale ? '（过期）' : ''}`
      : '暂无数据'
    const dim = !item.hasData || stale

    // 水滴 pin 的颜色跟着状态走（本轮新增的"空中 pin"实体）
    if (handle.pin) handle.pin.billboard.image = pinImage(pinColorOf(item))
    handle.dot.point.color = isRate ? Cesium.Color.TRANSPARENT : color
    handle.dot.point.outlineColor = isRate ? color : Cesium.Color.WHITE.withAlpha(0.95)
    handle.dot.point.outlineWidth = isRate ? 4 : 3
    handle.dot.label.text = `${item.code}  ${value}`
    handle.dot.label.fillColor = color
    handle.mast.polyline.material = isRate
      ? new Cesium.PolylineDashMaterialProperty({ color: color.withAlpha(0.85), dashLength: 10 })
      : color.withAlpha(0.75)
    // 两种情况亮环：① 该点有未解除警情（持续亮）；② 刚收到告警事件（闪几秒）
    const showRing = visual.key === 'alarm' || pulsing.has(item.id)
    if (showRing && !handle.ring) handle.ring = drawRing(item, handle.baseHeight)
    if (handle.ring) {
      handle.ring.ellipse.outlineColor = color.withAlpha(0.55)
      handle.ring.show = showRing
    }
    handle.visual = visual
  }

  return {
    /** 全量同步：新增缺失的点、更新已有的点、删除多余的点 */
    sync(items) {
      const seen = new Set()
      // 同一场景内的测点标签做阶梯错位，避免 4 个点叠成一坨看不清
      const stackCounter = {}
      for (const item of items) {
        seen.add(item.id)
        const group = item.sceneName || 'default'
        const groupIndex = stackCounter[group] ?? 0
        const stackIndex = groupIndex % 4
        stackCounter[group] = groupIndex + 1
        const existing = handles.get(item.id)
        if (existing) {
          existing.item = item
          paint(existing, item)
        } else {
          const handle = drawItem(item, stackIndex)
          if (handle) {
            paint(handle, item)
            handles.set(item.id, handle)
          }
        }
      }
      for (const [id, handle] of [...handles.entries()]) {
        if (seen.has(id)) continue
        viewer.entities.remove(handle.mast)
        viewer.entities.remove(handle.dot)
        if (handle.ring) viewer.entities.remove(handle.ring)
        handles.delete(id)
      }
    },

    /** 取某个点当前的显示口径（侧栏、图例复用） */
    visualOf(pointId) {
      return handles.get(pointId)?.visual || resolvePointVisual({})
    },

    applyTerrainHeights,

    /** 场景配置可按项目收紧标签距离；1000 点时远景不绘制全部文字。 */
    setLabelDistance(distance) {
      const value = Number(distance)
      if (!Number.isFinite(value) || value < 10) return
      labelDistance = value
      for (const handle of handles.values()) {
        handle.dot.label.distanceDisplayCondition = new Cesium.DistanceDisplayCondition(0, value)
      }
    },

    /**
     * 告警脉冲：让某个点的扩散环亮 `durationMs` 毫秒。
     *
     * 为什么要有这个（而不是只靠 `visual.key === 'alarm'`）：
     * 「这个点现在有未解除警情」和「刚刚又推来一条告警」是两件事——
     * 后者是**事件**，一闪而过才符合直觉；否则同一级别重复升级时，
     * 界面上什么变化都看不到（环本来就亮着）。
     */
    flash(pointId, durationMs = 6000) {
      const handle = handles.get(pointId)
      if (!handle) return
      pulsing.add(pointId)
      paint(handle, handle.item)
      setTimeout(() => {
        pulsing.delete(pointId)
        const current = handles.get(pointId)
        if (current) paint(current, current.item)
      }, durationMs)
    },

    /**
     * 拾取点击：返回点号 id，点空白处返回 null。
     *
     * **只认那颗点（kind='point'）**：立柱、热力晕圈这些同样带 pointId 的实体不能算点击目标
     * —— 热力晕圈半径能到上千米，若它可拾取，整片地面都会变成「点了就弹窗」，
     * 想关掉弹窗反而关不掉。
     */
    pickId(picked) {
      const id = picked?.id
      if (!id) return null
      if (!(id instanceof Cesium.Entity)) return null
      if (id.properties?.kind?.getValue?.() !== 'point') return null
      const value = id.properties?.pointId?.getValue?.()
      return typeof value === 'number' ? value : null
    },

    destroy() {
      if (removeHeightHandler) {
        removeHeightHandler()
        removeHeightHandler = null
      }
      for (const handle of handles.values()) {
        viewer.entities.remove(handle.mast)
        viewer.entities.remove(handle.dot)
        if (handle.pin) viewer.entities.remove(handle.pin)
        if (handle.ring) viewer.entities.remove(handle.ring)
      }
      handles.clear()
      pulsing.clear()
    },
  }
}
