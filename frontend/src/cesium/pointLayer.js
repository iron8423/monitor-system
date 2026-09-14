import * as Cesium from 'cesium'

import { resolvePointVisual } from '@/constants/status'
import { formatSigned } from '@/utils/format'

/** 测点上方立柱高度（米）：点形变雷达装在构筑物上，用一根立柱表示"测点在哪儿" */
const MAST_HEIGHT = 90
/** 告警脉冲环的最大半径（米） */
const RING_MAX = 95

/**
 * 测点图层：负责把"数据"翻译成"3D 实体"，并支持原地更新。
 *
 * 对外只暴露 sync(items) / highlight(pointId) 两个动作，
 * 数据从哪来（快照还是 SSE）它不关心 —— 这就是数据与视图解耦。
 *
 * 数据里跟测项有关的部分只有三样：`value`（当前主测项的值）、`unit`、`metricName`。
 * 它不认 `defo_mm` 这种具体测项代码——换一种测项，这一层一个字都不用改。
 */
export function createPointLayer(viewer) {
  /** pointId -> { mast, dot, ring, item } */
  const handles = new Map()
  /** 正在闪的点（SSE 刚推来告警）：这些点即使没有未解除警情也要亮环 */
  const pulsing = new Set()

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

    // 告警时外面套一圈会扩散的环，肉眼一眼能看到"哪个点在报"
    const ring = viewer.entities.add({
      id: `ring-${item.id}`,
      position: Cesium.Cartesian3.fromDegrees(lon, lat, ground + 1),
      ellipse: {
        semiMajorAxis: new Cesium.CallbackProperty(
          () => 18 + (Math.sin(Date.now() / 420) * 0.5 + 0.5) * RING_MAX * 0.42,
          false,
        ),
        semiMinorAxis: new Cesium.CallbackProperty(
          () => 18 + (Math.sin(Date.now() / 420) * 0.5 + 0.5) * RING_MAX * 0.42,
          false,
        ),
        material: new Cesium.ColorMaterialProperty(
          new Cesium.CallbackProperty(
            () => color.withAlpha(0.28 - (Math.sin(Date.now() / 420) * 0.5 + 0.5) * 0.22),
            false,
          ),
        ),
        outline: true,
        outlineColor: color.withAlpha(0.55),
        outlineWidth: 1.5,
        height: 0,
      },
      properties: { pointId: item.id },
    })
    ring.show = false

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
        distanceDisplayCondition: new Cesium.DistanceDisplayCondition(0, 20000),
        disableDepthTestDistance: Number.POSITIVE_INFINITY,
      },
      properties: { pointId: item.id, kind: 'point' },
    })

    return { item, mast, dot, ring, color, baseHeight: ground }
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
      handle.ring.position = new Cesium.ConstantPositionProperty(
        Cesium.Cartesian3.fromDegrees(lon, lat, height + 1),
      )
    }
  }

  function paint(handle, item) {
    const visual = resolvePointVisual(item)
    const color = Cesium.Color.fromCssColorString(visual.color)
    const value = item.hasData ? `${formatSigned(item.value, 2)}${item.unit || ''}` : '暂无数据'

    handle.dot.point.color = color
    handle.dot.label.text = `${item.code}  ${value}`
    handle.dot.label.fillColor = item.hasData ? Cesium.Color.WHITE : Cesium.Color.fromCssColorString('#b8c0cc')
    handle.mast.polyline.material = color.withAlpha(0.75)
    handle.ring.ellipse.outlineColor = color.withAlpha(0.55)
    // 两种情况亮环：① 该点有未解除警情（持续亮）；② 刚收到告警事件（闪几秒）
    handle.ring.show = visual.key === 'alarm' || pulsing.has(item.id)
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
        const stackIndex = stackCounter[group] = (stackCounter[group] ?? 0)
        stackCounter[group] += 1
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
        viewer.entities.remove(handle.ring)
        handles.delete(id)
      }
    },

    /** 取某个点当前的显示口径（侧栏、图例复用） */
    visualOf(pointId) {
      return handles.get(pointId)?.visual || resolvePointVisual({})
    },

    applyTerrainHeights,

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
        viewer.entities.remove(handle.ring)
      }
      handles.clear()
      pulsing.clear()
    },
  }
}
