import * as Cesium from 'cesium'

import { resolvePointVisual } from '@/constants/status'
import { formatSigned } from '@/utils/format'

/** 测点上方立柱高度（米）：点形变雷达装在构筑物上，用一根立柱表示"测点在哪儿" */
const MAST_HEIGHT = 14
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

    const dot = viewer.entities.add({
      id: `point-${item.id}`,
      position: top,
      point: {
        pixelSize: 16,
        color,
        outlineColor: Cesium.Color.WHITE.withAlpha(0.95),
        outlineWidth: 3,
        disableDepthTestDistance: Number.POSITIVE_INFINITY,
      },
      // 标签文字在 sync() 里随数据一起更新
      label: {
        text: item.code,
        font: '13px "Microsoft YaHei", sans-serif',
        fillColor: Cesium.Color.WHITE,
        showBackground: true,
        backgroundColor: Cesium.Color.fromCssColorString('#06101f').withAlpha(0.78),
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

    return { item, mast, dot, ring: null, color, baseHeight: ground }
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

    handle.dot.point.color = isRate ? Cesium.Color.TRANSPARENT : color
    handle.dot.point.outlineColor = isRate ? color : Cesium.Color.WHITE.withAlpha(0.95)
    handle.dot.point.outlineWidth = isRate ? 4 : 3
    handle.dot.label.text = `${item.code}  ${value}`
    handle.dot.label.fillColor = dim ? Cesium.Color.fromCssColorString('#b8c0cc') : Cesium.Color.WHITE
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
      for (const handle of handles.values()) {
        viewer.entities.remove(handle.mast)
        viewer.entities.remove(handle.dot)
        if (handle.ring) viewer.entities.remove(handle.ring)
      }
      handles.clear()
      pulsing.clear()
    },
  }
}
