import * as Cesium from 'cesium'

import { heatColorBucketOf, heatExtentOf, heatRadiusOf } from '@/utils/heatScale'

/**
 * 测值热力层（2026-09-20 重做标度）：在测点位置铺一层半透明晕圈，重叠处自然加深，
 * 让「哪一片的形变大」一眼可见。
 *
 * <h3>它是什么（先把这个说清楚）</h3>
 * 平台上每个测点只有一个数，没有面状采样，所以这一层**不是插值出来的连续场**，
 * 而是「每个测点一片晕圈」的叠加：**颜色 = 读数大小**（发散色标，负冷正暖，刻度见 utils/heatScale），
 * **半径 = 相对大小的视觉强调**（把 |值| 归一化到 [24m, 90m]）。半径没有物理含义，
 * 不是"影响半径"，图例里也是这么写的。
 *
 * <h3>只画选中的那一个测点（2026-09-20 用户要求）</h3>
 * 一开始是"所有测点各铺一片"，在 7 个点的演示场景上已经把山体盖成一片花斑，到了上百个点
 * 更是没法看（那时只能按 |值| 取前 N 个，等于把"哪一片形变大"交给了一个看不见的排序）。
 * 现在的口径与「垂直视场边界」一致：**只给当前选中的测点画**——没选中就不画。
 * 好处是任何时候屏幕上最多一片，位置与大小都跟"你正在看的那个点"对应得上；
 * 代价是失去"一眼扫全场的分布"，那一步等真有面状数据/等值线再说。
 *
 * <h3>上一版为什么看不出来</h3>
 * 颜色取的是**状态色**（正常=绿）→ 演示场景里测点普遍正常，所有圈同一个绿；
 * 半径 = 12m + |值| × 每单位米数，而形变速率只有 ±1mm/d → 全部贴在下限上，
 * 在几百米尺度的小山体上就是几个小点。两处叠加，这层等于白画。
 *
 * <h3>落地方式</h3>
 * 山体是独立 GLB，Cesium 的 `CLAMP_TO_GROUND` 只能贴椭球/terrain，不能贴 GLB，
 * 所以这一层**不能**用地形钳制。上一版把晕圈画在"档案高程 + 0.45m"的水平面上，
 * 结果整片被山体三角形吞掉——界面上勾了「地面热力图」什么都不出现（2026-09-20
 * 用户实测：放大了也看不到）。这与阈值线那次是同一类问题：**视觉上的死代码**。
 *
 * 现在改成**有厚度的短柱**：底面在档案高程下方一点、顶面抬高若干米。柱体是三维的，
 * 即便局部地形起伏把底面埋了，顶面与侧面仍然看得见；代价是它在陡坡上会略微"浮"起来
 * ——图例里已经写明"晕圈大小只是相对大小的示意，不是影响半径"，浮起不构成误导。
 * 材质是一张画好的径向渐变贴图（按**量化后的**颜色缓存，别每帧重画、也别为
 * 连续色标生成上千张小画布）。
 */

/** 柱体厚度参数（米）：底面下沉、顶面抬高的量 */
const PILLAR_BELOW_M = 2
const PILLAR_ABOVE_M = 10

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
  // 渐变形状是"读数能不能一眼看出来"的关键：上一版 0.6/0.27/0 太柔，铺在卫星底图上
  // 只剩一层若有若无的灰（用户反馈"看着很不明显"）。现在核心更实、中段保持可见，
  // 只在外缘快速淡出——既读得出来，又不会糊成一块。
  gradient.addColorStop(0, `rgba(${red},${green},${blue},${alpha})`)
  gradient.addColorStop(0.55, `rgba(${red},${green},${blue},${alpha * 0.55})`)
  gradient.addColorStop(1, `rgba(${red},${green},${blue},0)`)
  ctx.fillStyle = gradient
  ctx.fillRect(0, 0, TEXTURE_SIZE, TEXTURE_SIZE)

  textureCache.set(key, canvas)
  return canvas
}

// 不再需要「最多画 N 个」的预算参数：任何时候最多只画**一个**（当前选中的测点），见类注释。
// 原来的数字孪生配置项 maxHeatPoints 随之失效——后端字段保留是为兼容既有数据，前端不再读它。
export function createHeatmapLayer(viewer) {
  /** pointId -> entity */
  const handles = new Map()
  /** 当前这一轮的颜色标度上界（当前测点集合的最大绝对值）；由 sync 传进来 */
  let extent = 0

  function draw(item) {
    const lon = Number(item.longitude)
    const lat = Number(item.latitude)
    const ground = Number(item.altitude) || 0
    if (!Number.isFinite(lon) || !Number.isFinite(lat)) return null
    const radius = heatRadiusOf(item.value, extent)
    if (!radius) return null

    // 长、短半轴复用同一个属性：半径缩小时只更新一次，避免 Cesium 在两次
    // 顺序赋值之间看到“新长轴 < 旧短轴”的非法中间状态。
    const radiusProperty = new Cesium.ConstantProperty(radius)
    const entity = viewer.entities.add({
      id: `heat-${item.id}`,
      position: Cesium.Cartesian3.fromDegrees(lon, lat, ground + 0.45),
      ellipse: {
        semiMajorAxis: radiusProperty,
        semiMinorAxis: radiusProperty,
        height: ground - PILLAR_BELOW_M,
        extrudedHeight: ground + PILLAR_ABOVE_M,
        material: new Cesium.ImageMaterialProperty({
          image: gradientTexture(heatColorBucketOf(item.value, extent), 0.75),
          transparent: true,
        }),
        // 外缘描边：半径是"示意"不是实测范围，一圈清晰的边让人一眼看到它的边界在哪，
        // 也避免柔光被底图纹理吃掉（这是"看不出来"的第二个原因）。
        outline: true,
        outlineColor: Cesium.Color.fromCssColorString(
          heatColorBucketOf(item.value, extent)).withAlpha(0.85),
      },
      properties: { pointId: item.id, kind: 'heat' },
    })
    entity.show = false
    return { entity, radius, radiusProperty }
  }

  function paint(handle, item) {
    const radius = heatRadiusOf(item.value, extent)
    if (!radius) {
      handle.entity.show = false
      return
    }
    handle.entity.show = true
    handle.radiusProperty.setValue(radius)
    handle.entity.ellipse.material = new Cesium.ImageMaterialProperty({
      image: gradientTexture(heatColorBucketOf(item.value, extent), 0.75),
      transparent: true,
    })
    handle.entity.ellipse.outlineColor = Cesium.Color.fromCssColorString(
      heatColorBucketOf(item.value, extent)).withAlpha(0.85)
    handle.radius = radius
  }

  return {
    /**
     * 全量同步。`visible` 为 false 时整层隐藏（但实体保留，开回来不用重建）。
     * 拾取要穿透这层：热力实体也带 pointId，点上去应当当普通测点处理 —— 见 pickId 的过滤。
     */
    /**
     * @param items      当前屏幕上的测点
     * @param visible    图层开关
     * @param selectedId 当前选中的测点 id；为空则**一片都不画**（见类注释"只画选中的那一个"）
     */
    sync(items, { visible = true, selectedId = null } = {}) {
      /*
       * 标度上界**在这一层自己算**，不由调用方传（2026-09-20 踩过的坑）：
       * 原先签名里带 `max`，而有一个调用点忘了传 → `extent = 0` →
       * `heatRadiusOf` 一律返回 0 → 这一批晕圈被**全部清掉**；
       * 症状是"有时候有、有时候什么都没有"，排查时极易被当成渲染问题。
       * 标度只依赖 items，就让它只依赖 items——少一个能传错的东西。
       */
      extent = heatExtentOf(items)
      // 只保留选中那一个：见类注释——铺全场在山体上就是一层花斑，而且没法解释"为什么这块大那块小"。
      // 没选中时 selected 为空集，下面的循环会把已有实体全部清掉（这正是要的：不选中就不显示）。
      const wanted = selectedId == null
        ? []
        : (items || []).filter((item) => String(item.id) === String(selectedId))
      const selected = new Set(
        wanted
          .filter((item) => heatRadiusOf(item.value, extent) > 0)
          .map((item) => item.id),
      )
      const seen = new Set()
      for (const item of items || []) {
        seen.add(item.id)
        if (!selected.has(item.id)) {
          const skipped = handles.get(item.id)
          if (skipped) {
            viewer.entities.remove(skipped.entity)
            handles.delete(item.id)
          }
          continue
        }
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
        if (handle) handle.entity.show = visible && heatRadiusOf(item.value, extent) > 0
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
        handle.entity.show = visible && heatRadiusOf(handle.item?.value, extent) > 0
      }
    },

    destroy() {
      for (const handle of handles.values()) viewer.entities.remove(handle.entity)
      handles.clear()
    },
  }
}
