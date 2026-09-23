import * as Cesium from 'cesium'

/*
 * 屏幕空间标签避让（2026-09-23 第四轮）。
 *
 * 问题：一个演示场址里 7 个测点只隔一两百米、5 个分区又都指着一片地，
 * 相机一拉远，标签就在屏幕上挤成一坨——数值读不出来，分区名还压在测点数值上。
 * 上一轮的"按序号阶梯上移"是**全局档位**：横向离得远的标签也被一起抬高，
 * 而且档位用满之后又开始重合。
 *
 * 这里做**按屏幕坐标的真避让**：只有标签矩形真的相交才让位。
 * 两个图层（zoneLayer 的分区名、pointLayer 的测点）共用这一份实现，
 * 并且**测点给分区让位**——分区名是"这一片叫什么"（结构信息，被压住就不知道在看哪儿），
 * 测点数值是细节，被顶高几级仍然读得出来。
 *
 * 算法（贪心 + 行桶索引）：
 *   ① 锚点先投影成**窗口坐标**（CSS 像素，和 `label.pixelOffset` 同一坐标系）；
 *   ② 按屏幕 y **从下往上**逐个下放：屏幕下方的锚点先挑位置，最终标签自上而下的顺序
 *      与锚点顺序一致，不会出现"下面的点的标签跑到上面去"这种引线交叉；
 *   ③ 每个标签先试"贴锚点上方 gap 像素"，与已放置矩形（含外部障碍）相交就再抬一级，
 *      最多 maxLevel 级；
 *   ④ 已放置矩形按 bandPx 高的行桶索引，查询只扫相交的那两三行，
 *      1000 个点也不会退化成平方复杂度。
 */

const FONT_STACK = '"Microsoft YaHei", sans-serif'
const WIDTH_CACHE_MAX = 600

const widthCache = new Map()
let measureCtx = null

/**
 * 实测文字宽度（像素，未乘缩放）。
 *
 * 用 canvas 的 measureText，而不是"字数 × 系数"：标签里既有中文点号（P-HK01）、
 * 又有数值（-0.79mm）和全角括号（（过期）），按字数估会有三成以上偏差，
 * 偏小就会漏判重叠。结果按"字体 + 文字"缓存，避免每帧测几百次。
 */
export function textWidthPx(text, fontPx) {
  const key = `${fontPx}|${text}`
  const cached = widthCache.get(key)
  if (cached !== undefined) return cached
  if (!measureCtx) {
    const canvas = document.createElement('canvas')
    measureCtx = canvas.getContext('2d')
  }
  let width = String(text).length * fontPx * 0.6
  try {
    measureCtx.font = `${fontPx}px ${FONT_STACK}`
    width = measureCtx.measureText(String(text)).width
  } catch {
    /* 拿不到 2D 上下文（极老浏览器）时退回上面的估算值 */
  }
  if (widthCache.size > WIDTH_CACHE_MAX) widthCache.clear()
  widthCache.set(key, width)
  return width
}

/** 标签背景框尺寸（像素）：字号 × 缩放 + 背景内边距 */
export function labelBoxSize(text, fontPx, scale, padX = 8, padY = 4) {
  return {
    width: textWidthPx(text, fontPx) * scale + padX * 2,
    height: fontPx * 1.25 * scale + padY * 2,
  }
}

/** 网格单元尺寸（像素）：查询只扫相交的几格，1000 个点也不退化成平方复杂度 */
const CELL_W = 96
const CELL_H = 24

function makeIndex() {
  /** "bx|by" -> [{ x0, x1, y0, y1 }] */
  const cells = new Map()
  const put = (box) => {
    const bx0 = Math.floor(box.x0 / CELL_W)
    const bx1 = Math.floor(box.x1 / CELL_W)
    const by0 = Math.floor(box.y0 / CELL_H)
    const by1 = Math.floor(box.y1 / CELL_H)
    for (let bx = bx0; bx <= bx1; bx++) {
      for (let by = by0; by <= by1; by++) {
        const key = `${bx}|${by}`
        const bucket = cells.get(key)
        if (bucket) bucket.push(box)
        else cells.set(key, [box])
      }
    }
  }
  const hits = (box) => {
    const bx0 = Math.floor(box.x0 / CELL_W)
    const bx1 = Math.floor(box.x1 / CELL_W)
    const by0 = Math.floor(box.y0 / CELL_H)
    const by1 = Math.floor(box.y1 / CELL_H)
    for (let bx = bx0; bx <= bx1; bx++) {
      for (let by = by0; by <= by1; by++) {
        const bucket = cells.get(`${bx}|${by}`)
        if (!bucket) continue
        for (const other of bucket) {
          if (
            box.x0 < other.x1 && box.x1 > other.x0 &&
            box.y0 < other.y1 && box.y1 > other.y0
          ) {
            return true
          }
        }
      }
    }
    return false
  }
  return { put, hits }
}

/**
 * 分配每个标签的位置（就地写入 `item.offsetX` / `item.offsetY` / `item.level` / `item.rect`）。
 *
 * **先竖直、必要时横向**：
 *   候选位置按"位移代价"从小到大试，竖直抬一级算 1，横向挪半个标签宽算 1.5
 *   —— 所以近处总是优先"往上摞"（标签还在自己点的正上方，最容易读），
 *   只有当上头被别人占满（比如分区名就压在这一列）时才往左右让开。
 *   纯竖直方案在演示场址上会撞墙：7 个测点 + 5 个分区名挤在一片，
 *   只往上摞会顶到级数上限、剩下的标签还是重合（2026-09-23 实测）。
 *
 * @param {Array<{x:number,y:number,width:number,height:number,baseOffsetY?:number}>} items
 * @param {object} [opts]
 * @param {number} [opts.gapPx]      第一级标签底边离锚点的像素
 * @param {number} [opts.stepPx]     每让一级往上抬多少像素
 * @param {number} [opts.maxLevel]   最多让几级
 * @param {number} [opts.padX]       横向留白
 * @param {number} [opts.maxShift]   最多横向挪几个"标签宽"
 * @param {boolean}[opts.allowShift] 是否允许横向错开（点特别多时关掉省算力）
 * @param {Array}  [opts.obstacles]  外部障碍矩形（别的图层已经占好的位置）
 * @param {{w:number,h:number}} [opts.canvas] 画布尺寸：优先挑屏幕内的位置
 * @returns {number} 参与避让的标签数
 */
export function layoutLabels(items, opts = {}) {
  if (!items.length) return 0
  const gapPx = opts.gapPx ?? 24
  const stepPx = opts.stepPx ?? 22
  const maxLevel = opts.maxLevel ?? 8
  const padX = opts.padX ?? 3
  const maxShift = opts.maxShift ?? 1
  const allowShift = opts.allowShift ?? items.length <= 200
  const canvas = opts.canvas
  const index = makeIndex()

  // 外部障碍先占位：本层标签会绕开它们
  for (const rect of opts.obstacles || []) index.put(rect)

  items.sort((a, b) => b.y - a.y)
  let laidOut = 0
  for (const item of items) {
    const halfW = item.width / 2 + padX
    const base = item.baseOffsetY ?? -gapPx
    const shiftUnit = item.width / 2 + 8
    /** 候选项：代价越小越优先（竖直上升 cheapest，其次横向让开） */
    const candidates = []
    for (let level = 0; level <= maxLevel; level++) {
      candidates.push({ level, dx: 0, dy: base - level * stepPx, cost: level })
    }
    if (allowShift) {
      for (let level = 0; level <= Math.min(maxLevel, 4); level++) {
        for (let k = 1; k <= maxShift; k++) {
          const cost = level + k * 1.5
          const dy = base - level * stepPx
          candidates.push({ level, dx: shiftUnit * k, dy, cost })
          candidates.push({ level, dx: -shiftUnit * k, dy, cost })
        }
      }
    }
    candidates.sort((a, b) => a.cost - b.cost)

    let chosen = null
    let fallback = null
    for (const candidate of candidates) {
      const x0 = item.x + candidate.dx - halfW
      const x1 = item.x + candidate.dx + halfW
      const y0 = item.y + candidate.dy - item.height
      const y1 = item.y + candidate.dy
      const box = { x0, x1, y0, y1 }
      if (index.hits(box)) continue
      if (!fallback) fallback = { ...candidate, box }
      const inside =
        !canvas ||
        (y0 >= 2 && y1 <= canvas.h - 2 && x1 >= 2 && x0 <= canvas.w - 2)
      if (inside) {
        chosen = { ...candidate, box }
        break
      }
    }
    const result = chosen || fallback || (() => {
      const last = candidates[candidates.length - 1]
      return {
        ...last,
        box: {
          x0: item.x + last.dx - halfW,
          x1: item.x + last.dx + halfW,
          y0: item.y + last.dy - item.height,
          y1: item.y + last.dy,
        },
      }
    })()
    index.put(result.box)
    item.offsetX = result.dx
    item.offsetY = result.dy
    item.level = result.level
    item.rect = result.box
    laidOut += 1
  }
  return laidOut
}

const badPositionWarned = new Set()

/**
 * 世界坐标 → 窗口坐标（CSS 像素）；相机背面、算不出来、**或者坐标本身是坏的**时返回 null。
 *
 * ⚠️ 必须把所有异常都吞掉（2026-09-23 实测事故）：
 * `SceneTransforms.worldToWindowCoordinates` 内部会 `Cartesian3.normalize`，
 * 传进去的坐标只要有 NaN 分量（哪怕只有一个点坏），它抛的是
 * `DeveloperError: normalized result is not a number` —— 而调用点在 `postRender` 里，
 * 这个异常直接把**整帧渲染打断**，Cesium 弹"Rendering has stopped"，大屏冻住。
 * 布局是"锦上添花"的功能，绝不能因为一个坏坐标把整个三维视图搞停。
 */
export function toWindow(scene, position) {
  if (
    !position ||
    !Number.isFinite(position.x) ||
    !Number.isFinite(position.y) ||
    !Number.isFinite(position.z)
  ) {
    return null
  }
  try {
    const win = Cesium.SceneTransforms.worldToWindowCoordinates(scene, position)
    return win && Number.isFinite(win.x) && Number.isFinite(win.y) ? win : null
  } catch (error) {
    // 同一个坐标只吵一次，别每帧刷屏
    const key = `${Math.round(position.x)}|${Math.round(position.y)}|${Math.round(position.z)}`
    if (!badPositionWarned.has(key)) {
      badPositionWarned.add(key)
      if (badPositionWarned.size > 20) badPositionWarned.clear()
      console.warn('[labelLayout] 跳过无法投影的坐标', key, error?.message)
    }
    return null
  }
}

/**
 * 读标签文字。
 *
 * 坑（2026-09-23 实测踩到）：从实体上读 `entity.label.text` 拿到的是 **Property 对象**，
 * 不是字符串——`String(property)` 会得到 `"[object Object]"`（15 个字符），
 * 于是字宽按这个量、比真实标签窄一半，避让就会漏判重叠（画面上一片压字）。
 * 这里统一用 `getValue(time)` 求值。
 */
export function readLabelText(property, time) {
  if (property === undefined || property === null) return ''
  if (typeof property === 'string') return property
  if (typeof property.getValue === 'function') {
    try {
      return String(property.getValue(time) ?? '')
    } catch {
      return ''
    }
  }
  return String(property)
}
