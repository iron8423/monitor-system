import * as Cesium from 'cesium'

import { labelBoxSize, layoutLabels, readLabelText, toWindow } from '@/cesium/labelLayout'

/*
 * 分区图层（2026-09-23 用户需求）：
 *   全局视角 → 点某个分区 → 相机聚焦到该分区、只看这个分区的设备与测点。
 *
 * 为什么单独一层：分区的**几何与语义**（在哪、多大、叫什么、包含哪些设备）是场景数据，
 * 不该写死在 ScreenView 的模板里；换场址时只换 `public/zones.json` 即可。
 *
 * 落地方式（刻意保守）：用 entity 画"贴地椭圆 + 浮空标签 + 引线"，
 * 高度取 zones.json 里从地形采样出来的本地高程 + 场景锚点高度——
 * 我们的地面是一个 GLB 模型而不是 globe 地形，所以**不能用 CLAMP_TO_GROUND**（那会贴到椭球上）。
 *
 * 2026-09-23 第二轮（用户反馈"标签不会随缩放自适应"）：
 *   标签高度、字号、引线粗细全部改成**跟着相机离地高度自适应**：
 *     相机贴地 → 标签放低（几乎贴着分区中心）、字号小一点，不挡场景；
 *     相机拉高 → 标签抬起、字号放大，远看仍然点得出名字。
 *   以前是 `max(60, 半径×0.45)` 的死值：近看那 60 m 的立柱像根天线，
 *   远看 15px 的字又糊在建筑群里。现在用一条 postRender 节流循环统一更新。
 */

const FILL = Cesium.Color.fromCssColorString('#4ea8ff')
const ACTIVE_FILL = Cesium.Color.fromCssColorString('#ffb454')

/** 标签浮空高度（米）随相机离地高度的比例与上下限 */
const LABEL_MIN_M = 12
const LABEL_MAX_M = 420
/*
 * 比例要比测点（pointLayer 的 LIFT_RATIO = 0.08）**更大**：
 * 分区名是"这一片叫什么"，测点是"哪个点在读数"，两者必须在屏幕上分层——
 * 用同一个比例时它们的浮空高度几乎相同，拉远之后分区名会和测点标签叠在一起
 * （2026-09-23 实测：远视角下"山脊监测区（北）"正好压在 P-HK0x 那一列上）。
 *
 * 2026-09-23 第四轮：光靠高度分层还不够（相机一拉远，屏幕上的距离照样挤到一起），
 * 现在分区名自己也走 `labelLayout.layoutLabels` 做屏幕空间避让（5 个分区名互相不压），
 * 并把占用的矩形交给测点图层当障碍——**测点给分区名让位**。
 */
const LABEL_RATIO = 0.16
/** 字号随相机高度自适应：贴地时小、拉远时大（label.scale 是字号的倍数） */
const LABEL_SCALE_NEAR = 0.82
const LABEL_SCALE_FAR = 1.22
const SCALE_FROM_M = 120
const SCALE_TO_M = 2200
/** 分区名的字号（与 build() 里写的 font 一致） */
const ZONE_FONT_PX = 16
/** 分区名标签底边离引线顶端多少像素（与原来的固定值 -6 一致） */
const ZONE_LABEL_GAP_PX = 6

export function createZoneLayer(viewer, { onSelect } = {}) {
  const entities = []
  let zones = []
  let activeId = null
  let anchorHeight = 0
  /** zoneId -> { label, leader, ground, tip }：自适应循环要用的句柄 */
  const handles = new Map()
  /**
   * 上一帧分区名标签占用的屏幕矩形（CSS 像素）。
   * 测点图层会把它当**外部障碍**读走（`createPointLayer` 的 `obstacles` 选项），
   * 这样"P-HK03 −0.11mm"就不会再压在"边坡监测区（南）"上面。
   */
  let lastLabelRects = []

  function worldPosition(zone, extraHeight) {
    // zones.json 里存的是本地米，经纬度由调用方按场景锚点换算好（见 zoneLayer 的 setZones）
    return Cesium.Cartesian3.fromDegrees(
      Number(zone.longitude),
      Number(zone.latitude),
      anchorHeight + Number(zone.groundHeightM || 0) + extraHeight,
    )
  }

  /**
   * 场景锚点还没就绪时，`zones.json` 里的本地米还没换算成经纬度（longitude/latitude 是 undefined）。
   * 那种分区**必须跳过**：
   *   `Cartesian3.fromDegrees(NaN, …)` 会抛 `DeveloperError: normalized result is not a number`，
   *   把整层建到一半就中断——而这层平时是"先加载 JSON、场景稍后就绪"的顺序，很容易撞上。
   * （2026-09-23 实测：因为这个异常，后面的 preview.json / devices.json 全没加载，
   *   于是测点与雷达一直停在档案坐标上，分区里也全是 0 点。）
   */
  function isPlaced(zone) {
    return Number.isFinite(Number(zone?.longitude)) && Number.isFinite(Number(zone?.latitude))
  }

  function updateAdaptive() {
    if (!handles.size) {
      lastLabelRects = []
      return
    }
    const carto = Cesium.Cartographic.fromCartesian(viewer.camera.positionWC)
    const camHeight = carto ? Math.max(0, carto.height) : 0
    const h = Math.min(Math.max(camHeight * LABEL_RATIO, LABEL_MIN_M), LABEL_MAX_M)
    const t = Math.min(
      1,
      Math.max(0, (camHeight - SCALE_FROM_M) / (SCALE_TO_M - SCALE_FROM_M)),
    )
    const scale = LABEL_SCALE_NEAR + (LABEL_SCALE_FAR - LABEL_SCALE_NEAR) * t
    /*
     * 两趟：先算几何 + 投影，再统一避让。
     * 引线始终连到**标签实际所在的位置**，所以标签被顶高几级也不会"引线指空"。
     */
    const layoutItems = []
    for (const handle of handles.values()) {
      const tip = Cesium.Cartesian3.fromDegrees(
        handle.lon, handle.lat, handle.base + h,
      )
      handle.label.position = tip
      handle.label.label.scale = scale
      handle.leader.polyline.positions = [handle.ground, tip]
      // 从实体读回来的是 Property，不是字符串（见 labelLayout.readLabelText 的说明）
      const text = readLabelText(handle.label.label.text, viewer.clock.currentTime)
      const box = labelBoxSize(String(text), ZONE_FONT_PX, scale, 0, 0)
      const win = toWindow(viewer.scene, tip)
      layoutItems.push({
        handle,
        x: win ? win.x : Number.NaN,
        y: win ? win.y : Number.NaN,
        width: box.width,
        height: box.height,
        baseOffsetY: -ZONE_LABEL_GAP_PX,
        tip,
      })
    }
    // 视野外（投影不出来）的标签不参与避让，免得把屏幕内的标签白白顶高
    const visible = layoutItems.filter((item) => Number.isFinite(item.x) && Number.isFinite(item.y))
    if (visible.length) {
      layoutLabels(visible, {
        gapPx: ZONE_LABEL_GAP_PX,
        stepPx: 24,
        maxLevel: 6,
        canvas: {
          w: viewer.scene.canvas.clientWidth,
          h: viewer.scene.canvas.clientHeight,
        },
      })
      for (const item of visible) {
        item.handle.label.label.pixelOffset = new Cesium.Cartesian2(item.offsetX, item.offsetY)
        item.handle.labelLevel = item.level
        item.handle.labelOffsetX = item.offsetX || 0
        item.handle.labelOffsetY = item.offsetY || 0
      }
    }
    lastLabelRects = visible
      .map((item) => ({
        ...item.rect,
        zoneId: item.handle.zoneId,
        label: {
          zoneId: item.handle.zoneId,
          text: readLabelText(item.handle.label.label.text, viewer.clock.currentTime),
          level: item.level,
          rect: item.rect,
        },
      }))
      .filter((rect) => rect.x0 !== undefined)
  }

  let lastTick = 0
  const removeTick = viewer.scene.postRender.addEventListener(() => {
    const now = Date.now()
    if (now - lastTick < 140) return
    lastTick = now
    updateAdaptive()
  })

  function build() {
    for (const zone of zones) {
      if (!isPlaced(zone)) continue
      const active = zone.id === activeId
      const color = active ? ACTIVE_FILL : FILL
      entities.push(viewer.entities.add({
        id: `zone-area-${zone.id}`,
        position: worldPosition(zone, 1.0),
        ellipse: {
          semiMajorAxis: Number(zone.radiusM) || 150,
          semiMinorAxis: Number(zone.radiusM) || 150,
          // 填充压得很轻（2026-09-23：拉到最远时 5 片半透明面叠在一起像"糊了一屏蓝"）,
          // 归属信息主要交给轮廓与浮空标签去表达。
          material: color.withAlpha(active ? 0.16 : 0.07),
          outline: true,
          outlineColor: color.withAlpha(active ? 0.95 : 0.7),
          outlineWidth: 2,
        },
        properties: { zoneId: zone.id, kind: 'zone' },
      }))
      // 浮空标签 + 一条引线连到分区中心，远看也能点名（高度随相机自适应，见文件头）
      const labelHeight = Math.max(LABEL_MIN_M, (Number(zone.radiusM) || 150) * 0.2)
      const base = anchorHeight + Number(zone.groundHeightM || 0)
      const ground = worldPosition(zone, 0.6)
      const tip = worldPosition(zone, labelHeight)
      const label = viewer.entities.add({
        id: `zone-label-${zone.id}`,
        position: tip,
        label: {
          text: zone.name,
          font: `${active ? 'bold ' : ''}16px "Microsoft YaHei", sans-serif`,
          fillColor: Cesium.Color.WHITE,
          outlineColor: Cesium.Color.fromCssColorString('#0b1622'),
          outlineWidth: 3,
          style: Cesium.LabelStyle.FILL_AND_OUTLINE,
          verticalOrigin: Cesium.VerticalOrigin.BOTTOM,
          pixelOffset: new Cesium.Cartesian2(0, -6),
          disableDepthTestDistance: Number.POSITIVE_INFINITY,
        },
        properties: { zoneId: zone.id, kind: 'zone' },
      })
      const leader = viewer.entities.add({
        id: `zone-leader-${zone.id}`,
        polyline: {
          positions: [ground, tip],
          width: 1.5,
          material: color.withAlpha(0.6),
        },
        properties: { zoneId: zone.id, kind: 'zone' },
      })
      entities.push(label, leader)
      handles.set(zone.id, {
        zoneId: zone.id, label, leader, ground, base,
        lon: Number(zone.longitude), lat: Number(zone.latitude),
      })
    }
    updateAdaptive()
  }

  function clear() {
    for (const entity of entities) viewer.entities.remove(entity)
    entities.length = 0
    handles.clear()
  }

  function pickZone(position) {
    const picked = viewer.scene.pick(position)
    const zoneId = picked?.id?.properties?.zoneId?.getValue?.()
    if (!zoneId) return null
    return zones.find((zone) => zone.id === zoneId) || null
  }

  const clickHandler = new Cesium.ScreenSpaceEventHandler(viewer.scene.canvas)
  clickHandler.setInputAction((movement) => {
    const zone = pickZone(movement.position)
    if (zone) onSelect?.(zone)
  }, Cesium.ScreenSpaceEventType.LEFT_CLICK)

  const moveHandler = new Cesium.ScreenSpaceEventHandler(viewer.scene.canvas)
  moveHandler.setInputAction((movement) => {
    const hovering = Boolean(pickZone(movement.endPosition))
    viewer.scene.canvas.style.cursor = hovering ? 'pointer' : ''
  }, Cesium.ScreenSpaceEventType.MOUSE_MOVE)

  return {
    /** 设置分区数据（换场景或换场址时调用） */
    setZones(list, sceneAnchorHeight = 0) {
      zones = Array.isArray(list) ? list : []
      anchorHeight = Number(sceneAnchorHeight) || 0
      clear()
      build()
    },
    setActive(zoneId) {
      activeId = zoneId || null
      clear()
      build()
    },
    /**
     * 分区名标签当前占用的屏幕矩形（CSS 像素）。
     * 测点图层拿它当障碍用——`ScreenView` 里这样接：
     * `createPointLayer(viewer, { obstacles: () => zoneLayer?.labelRects() || [] })`
     */
    labelRects() {
      return lastLabelRects
    },
    /** 分区名标签的屏幕矩形与让位级数（诊断 / 无头验证用） */
    labelDiag() {
      return lastLabelRects.map((rect) => rect.label)
    },
    /**
     * 分区名被屏幕空间避让挪开时，给它补一条引线（屏幕空间叠加层绘制）。
     * 3D 那条 `zone-leader-*` 仍然指着引线顶端（= 分区中心的方位），
     * 这里补的是"顶端 → 标签实际位置"这一小段，避免标签挪开后看着悬空。
     */
    leaderSegments() {
      const out = []
      for (const handle of handles.values()) {
        const dx = handle.labelOffsetX || 0
        const dy = handle.labelOffsetY || 0
        if (Math.abs(dx) < 1 && Math.abs(dy + ZONE_LABEL_GAP_PX) < 2) continue
        const tip = handle.label.position?.getValue?.(viewer.clock.currentTime)
        if (!tip) continue
        out.push({ world: tip, dx, dy, color: 'rgba(255,255,255,0.45)' })
      }
      return out
    },
    get activeId() {
      return activeId
    },
    destroy() {
      clear()
      clickHandler.destroy()
      moveHandler.destroy()
      removeTick()
    },
  }
}
