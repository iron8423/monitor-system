import * as Cesium from 'cesium'

import { resolvePointVisual } from '@/constants/status'

/**
 * 测值热力层：在**地面**上按测点值铺一层半透明晕圈，重叠处自然加深，
 * 让「哪一片的形变大」一眼可见（原始需求里的「热力图」）。
 *
 * 为什么用「每个点一个径向渐变椭圆」而不是正经的插值热力图：数据源只有 7 个离散测点，
 * 做 IDW/Kriging 插值出来的面是**算出来的**、不是量出来的，图上却看不出来这层区别
 * —— 演示场景里「几个晕圈按其值大小和颜色叠出来」既诚实又够看。真正的插值场等
 * 有面状采样数据（或测点上百）再说。
 *
 * 落地方式：`ellipse` + `heightReference: CLAMP_TO_GROUND`（贴地形，不做成浮空面），
 * 材质是一张画好的径向渐变贴图（按颜色缓存，别每帧重画）。
 */

/** 晕圈半径（米）：基础 + 按 |值| 线性放大，封顶免得一个大值糊满全屏 */
const RADIUS_MIN = 260
const RADIUS_MAX = 1400

/**
 * 每个测项「每 1 个单位放大多少米」。
 *
 * 这是**呈现标度**，不是数据口径：mm 与 mm/d、℃ 的数值范围差着几个量级，
 * 用同一个每单位系数会让某一类测项要么糊满全屏、要么看不见。
 * 档案里目前没有量程字段，所以先在界面这一层配置；将来 metric 档案若加了 range，
 * 这里换成读档案即可（`heatScaleOf` 是唯一入口）。
 */
const RADIUS_PER_UNIT = {
  defo_mm: 80,
  rate_mm_d: 20,
}
const DEFAULT_RADIUS_PER_UNIT = 40

export function heatScaleOf(metricCode) {
  return RADIUS_PER_UNIT[metricCode] ?? DEFAULT_RADIUS_PER_UNIT
}

const TEXTURE_SIZE = 128
const textureCache = new Map()

function toRgb(color) {
  const c = Cesium.Color.fromCssColorString(color)
  return [Math.round(c.red * 255), Math.round(c.green * 255), Math.round(c.blue * 255)]
}

/** 径向渐变贴图：中心浓、边缘透明。同色只画一次（Map 缓存） */
function gradientTexture(color, alpha) {
  const key = `${color}|${alpha}`
  const cached = textureCache.get(key)
  if (cached) return cached

  const canvas = document.createElement('canvas')
  canvas.width = TEXTURE_SIZE
  canvas.height = TEXTURE_SIZE
  const ctx = canvas.getContext('2d')
  const r = TEXTURE_SIZE / 2
  const [red, green, blue] = toRgb(color)
  const gradient = ctx.createRadialGradient(r, r, 0, r, r, r)
  gradient.addColorStop(0, `rgba(${red},${green},${blue},${alpha})`)
  gradient.addColorStop(0.5, `rgba(${red},${green},${blue},${alpha * 0.45})`)
  gradient.addColorStop(1, `rgba(${red},${green},${blue},0)`)
  ctx.fillStyle = gradient
  ctx.fillRect(0, 0, TEXTURE_SIZE, TEXTURE_SIZE)

  textureCache.set(key, canvas)
  return canvas
}

/** 值 → 晕圈半径；没有值（null）返回 0，调用方据此隐藏 */
export function heatRadiusOf(value, metricCode) {
  const v = Math.abs(Number(value))
  if (!Number.isFinite(v)) return 0
  return Math.min(RADIUS_MAX, RADIUS_MIN + v * heatScaleOf(metricCode))
}

export function createHeatmapLayer(viewer) {
  /** pointId -> entity */
  const handles = new Map()

  function draw(item) {
    const lon = Number(item.longitude)
    const lat = Number(item.latitude)
    if (!Number.isFinite(lon) || !Number.isFinite(lat)) return null
    const radius = heatRadiusOf(item.value, item.metricCode)
    if (!radius) return null

    const visual = resolvePointVisual(item)
    const entity = viewer.entities.add({
      id: `heat-${item.id}`,
      position: Cesium.Cartesian3.fromDegrees(lon, lat, 0),
      ellipse: {
        semiMajorAxis: radius,
        semiMinorAxis: radius,
        // 贴地形：不写 height + CLAMP_TO_GROUND，坡地上才不会一半埋进地里
        heightReference: Cesium.HeightReference.CLAMP_TO_GROUND,
        classificationType: Cesium.ClassificationType.TERRAIN,
        material: new Cesium.ImageMaterialProperty({
          image: gradientTexture(visual.color, 0.5),
          transparent: true,
        }),
      },
      properties: { pointId: item.id, kind: 'heat' },
    })
    entity.show = false
    return { entity, visual: visual.key, radius }
  }

  function paint(handle, item) {
    const visual = resolvePointVisual(item)
    const radius = heatRadiusOf(item.value, item.metricCode)
    if (!radius) {
      handle.entity.show = false
      return
    }
    handle.entity.show = true
    handle.entity.ellipse.semiMajorAxis = radius
    handle.entity.ellipse.semiMinorAxis = radius
    handle.entity.ellipse.material = new Cesium.ImageMaterialProperty({
      image: gradientTexture(visual.color, 0.5),
      transparent: true,
    })
    handle.visual = visual.key
    handle.radius = radius
  }

  return {
    /**
     * 全量同步。`visible` 为 false 时整层隐藏（但实体保留，开回来不用重建）。
     * 拾取要穿透这层：热力实体也带 pointId，点上去应当当普通测点处理 —— 见 pickId 的过滤。
     */
    sync(items, { visible = true } = {}) {
      const seen = new Set()
      for (const item of items || []) {
        seen.add(item.id)
        const existing = handles.get(item.id)
        if (existing) {
          existing.item = item
          paint(existing, item)
        } else {
          const handle = draw(item)
          if (handle) {
            handles.set(item.id, { ...handle, item })
          }
        }
        const handle = handles.get(item.id)
        if (handle) handle.entity.show = visible && heatRadiusOf(item.value, item.metricCode) > 0
      }
      for (const [id, handle] of [...handles.entries()]) {
        if (seen.has(id)) continue
        viewer.entities.remove(handle.entity)
        handles.delete(id)
      }
    },

    /** 开关（界面上的「热力图」复选框） */
    setVisible(visible) {
      for (const handle of handles.values()) {
        handle.entity.show = visible && heatRadiusOf(handle.item?.value, handle.item?.metricCode) > 0
      }
    },

    destroy() {
      for (const handle of handles.values()) viewer.entities.remove(handle.entity)
      handles.clear()
    },
  }
}
