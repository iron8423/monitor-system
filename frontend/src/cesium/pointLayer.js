import * as Cesium from 'cesium'

import { resolvePointVisual } from '@/constants/status'
import { formatSigned } from '@/utils/format'
import { labelBoxSize, layoutLabels, readLabelText, toWindow } from '@/cesium/labelLayout'

/** 测点上方立柱高度（米）：点形变雷达装在构筑物上，用一根立柱表示"测点在哪儿" */
/*
 * 引线高度（2026-09-23 第三轮，用户反馈"图例漂在空中"）：
 *   以前默认 60 m 且动态档位最低也是 60 m —— 相机拉到最近（看全场）时，
 *   标签被抬到 60 m 高空，配合接近水平的视角就"漂在天上"，和地面完全脱节。
 *   现在改成"**跟着相机高度自适应，低处贴地**"：
 *     相机离地 < 250 m   → 标签几乎贴着地面（3 m）
 *     相机 250 m 以上    → 按相机高度的 8% 抬起，上限 120 m
 *   这样远看是"地面上的一排点"，近看才把标签抬起来避免互相压住。
 */
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
export function createPointLayer(viewer, { labelDistance = 2000, obstacles = () => [] } = {}) {
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
/*
 * 引线高度档位（2026-09-23 第三轮）：**低处贴地、高处才抬**。
 *   · HUG_M：相机贴地时的引线高度（标签几乎落在地面点上）
 *   · LIFT_FROM_M：从多高开始抬标签（比这低就一律贴地）
 *   · LIFT_RATIO / LIFT_MAX_M：抬起比例与上限
 * 这三个值都可环境变量覆盖，方便按不同场址的尺度微调。
 */
const HUG_M = parseFloat(import.meta.env.VITE_PIN_HUG_M) || 3
const LIFT_FROM_M = parseFloat(import.meta.env.VITE_PIN_LIFT_FROM_M) || 250
const LIFT_RATIO = parseFloat(import.meta.env.VITE_PIN_LIFT_RATIO) || 0.08
const LIFT_MAX_M = parseFloat(import.meta.env.VITE_PIN_HEIGHT_MAX_M) || 120
/*
 * 尺寸/可见距离随相机高度自适应（2026-09-23 用户反馈"缩放到最小，看不到字"）。
 *
 * 两个症状、两个原因：
 *   ① 缩到最远时**标签整个消失** —— 标签带 `distanceDisplayCondition: (0, labelDistance)`，
 *      而 labelDistance 来自档案默认 2000 m。相机一拉远，到远端测点的斜距超过 2 km，
 *      整片标签一起被裁掉（这曾经也被当"标签丢了"排查过）。现在这个上界**跟着相机长**：
 *      相机越高，允许看的距离越远，永远比"当前视野里的斜距"大一号。
 *   ② 拉近/拉远时水滴和字号**一样大** —— 屏幕像素恒定的代价是"近看太挤、远看太小"。
 *      现在按相机高度线性插值：贴地时小一点（0.85×、24px），拉远时大一点（1.25×、34px）。
 */
const SCALE_NEAR_M = 150
const SCALE_FAR_M = 2400
const LABEL_SCALE_NEAR = 0.85
const LABEL_SCALE_FAR = 1.25
const PIN_NEAR_PX = 24
const PIN_FAR_PX = 34
/** 标签可见距离的自适应系数：上界 = max(档案值, 相机离地 × 这个系数) */
const LABEL_DISTANCE_RATIO = 4.5
const LABEL_DISTANCE_MAX_M = 30000

/*
 * 标签避让（2026-09-23 第四轮）走公用实现 `labelLayout.js`：
 * 分区名与测点用同一套算法，且**测点给分区名让位**（见该文件头部的优先级说明）。
 * 这里只留本层自己的三个数值：
 */
/** 第一级：标签底边离锚点多少像素 */
const LABEL_GAP_PX = 24
/** 每让一级往上抬多少像素 */
const LABEL_LEVEL_STEP_PX = 22
/** 最多让几级（防极端情况下把标签顶出屏幕） */
const LABEL_MAX_LEVEL = 10
/** 测点标签的基准字号（与 `drawItem` 里的 font 保持一致） */
const LABEL_FONT_PX = 13

  let lastHeightTick = 0
  /** 上一帧参与避让的标签数（诊断用，见 labelDiag()） */
  let lastLabelLayoutCount = 0
  removeHeightHandler = viewer.scene.postRender.addEventListener(() => {
    const now = Date.now()
    if (now - lastHeightTick < 120) return
    lastHeightTick = now
    const carto = Cesium.Cartographic.fromCartesian(viewer.camera.positionWC)
    const camHeight = carto ? carto.height : 0
      const h = Math.min(
        Math.max((camHeight - LIFT_FROM_M) * LIFT_RATIO, HUG_M),
        LIFT_MAX_M,
      )
    const t = Math.min(1, Math.max(0, (camHeight - SCALE_NEAR_M) / (SCALE_FAR_M - SCALE_NEAR_M)))
    const labelScale = LABEL_SCALE_NEAR + (LABEL_SCALE_FAR - LABEL_SCALE_NEAR) * t
    const pinPx = PIN_NEAR_PX + (PIN_FAR_PX - PIN_NEAR_PX) * t
    /*
     * 阶梯错位的步长也要跟着放大（2026-09-23）：一个场址里的测点往往只隔一两百米，
     * 相机一拉远，它们的屏幕位置就挤到一起——固定 21px 的错位不够用，
     * 7 个标签会糊成一坨（"缩放到最小反而看不清"）。相机越高，步长越大，
     * 远看是一列整齐的清单，近看仍然贴着各自的点。
     */
    const labelFar = Math.min(
      LABEL_DISTANCE_MAX_M,
      Math.max(labelDistance, camHeight * LABEL_DISTANCE_RATIO),
    )
    /*
     * 标签避让要按**屏幕位置**算，所以这一趟循环只负责"几何 + 尺寸"，
     * 每个标签的锚点世界坐标顺手存进 layoutItems，循环结束后统一分配 pixelOffset。
     */
    const canvasW = viewer.scene.canvas.clientWidth
    const canvasH = viewer.scene.canvas.clientHeight
    const cameraPos = viewer.camera.positionWC
    const layoutItems = []
    for (const handle of handles.values()) {
      /*
       * 只认**有限数值**：`undefined` 与 `NaN` 都要跳过。
       * NaN 一路走到 `Cartesian3.fromDegrees` 会抛
       * `DeveloperError: normalized result is not a number`，而这个循环在 postRender 里，
       * 抛出去就是**整帧渲染中断**（Cesium 弹 "Rendering has stopped"，大屏冻住）。
       */
      if (
        !Number.isFinite(handle.lon) ||
        !Number.isFinite(handle.lat) ||
        !Number.isFinite(handle.baseHeight)
      ) {
        continue
      }
      const top = Cesium.Cartesian3.fromDegrees(handle.lon, handle.lat, handle.baseHeight + h)
      handle.mast.polyline.positions = [handle.bottom, top]
      handle.dot.position = top
      handle.dot.label.scale = labelScale
      handle.dot.label.distanceDisplayCondition =
        new Cesium.DistanceDisplayCondition(0, labelFar)
      if (handle.pin) {
        handle.pin.position = top
        handle.pin.billboard.width = pinPx
        handle.pin.billboard.height = pinPx
      }
      /*
       * 参与避让的三个前提：文字非空、在可见距离内、投影到屏幕上还算得出来。
       * 视野外的标签不参与，免得"看不见的标签"把看得见的顶上去。
       */
      // 注意：从实体上读回来的是 Property，不是字符串（见 labelLayout.readLabelText 的说明）
      const text = readLabelText(handle.dot.label.text, viewer.clock.currentTime)
      if (!text) continue
      if (Cesium.Cartesian3.distance(cameraPos, top) > labelFar) continue
      const win = toWindow(viewer.scene, top)
      if (!win) continue
      if (win.x < -canvasW || win.y < -canvasH || win.x > canvasW * 2 || win.y > canvasH * 2) {
        continue
      }
      const box = labelBoxSize(String(text), LABEL_FONT_PX, labelScale)
      layoutItems.push({
        handle,
        // 引线起点（世界坐标）随条目带着走：第二个循环里 `top` 已经出作用域，
        // 而 `top` 在浏览器里是**全局的 `window.top`**，写错会静默拿到一个 Window 对象
        // （2026-09-23 实测踩过：引线投影全失败、画面上一条都没有，且不报错）
        top,
        x: win.x,
        y: win.y,
        width: box.width,
        height: box.height,
      })
    }
    /*
     * 分区名（zoneLayer）已经占好的位置作为**外部障碍**传进来：
     * 测点数值宁可往上让几级，也不要压在"这是哪个分区"上面。
     */
    const laidOut = layoutLabels(layoutItems, {
      gapPx: LABEL_GAP_PX,
      stepPx: LABEL_LEVEL_STEP_PX,
      maxLevel: LABEL_MAX_LEVEL,
      obstacles: obstacles(),
      canvas: { w: canvasW, h: canvasH },
    })
    for (const item of layoutItems) {
      item.handle.dot.label.pixelOffset = new Cesium.Cartesian2(item.offsetX, item.offsetY)
      item.handle.labelLevel = item.level
      item.handle.labelRect = item.rect
      item.handle.labelOffsetX = item.offsetX || 0
      item.handle.labelOffsetY = item.offsetY || 0
      // 引线的起点（世界坐标）：点位本身，叠加层每帧把它投影成屏幕坐标
      item.handle.labelAnchor = item.top
    }
    if (layoutItems.length) lastLabelLayoutCount = laidOut
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
    const top = Cesium.Cartesian3.fromDegrees(lon, lat, ground + HUG_M)

    /*
     * 幂等添加（2026-09-23 修）：切换分区时点会被过滤掉，**再切回来**要重建实体。
     * 只要有任何一条路径漏删了同 id 的实体，`entities.add` 就会抛
     *   DeveloperError: An entity with id pin-3 already exists in this collection
     * 而它发生在 watch 里 → 整个 sync 中断，后面的点全都不同步（画面上"少了几个点"）。
     * 这里逐个 id 先摘再挂，任何来源的残留都不会再打断同步。
     */
    const addEntity = (options) => {
      const existing = viewer.entities.getById(options.id)
      if (existing) viewer.entities.remove(existing)
      return viewer.entities.add(options)
    }

    const mast = addEntity({
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
    const pin = addEntity({
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

    const dot = addEntity({
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

    /*
     * 位移引线**不在这里建实体**（2026-09-23 第四轮）：标签被避让顶高/挪开之后，
     * 需要一条线把它连回自己的点位。先用 Cesium 的 polyline 做，几何全对但屏幕上一条都看不见
     * ——根因是山体遮挡（详见 labelOverlay.js 的文件头说明）。现在改成屏幕空间叠加层画，
     * 这里只把"位移量"记在 handle 上，由 `leaderSegments()` 交出去。
     */
    return {
      item, mast, dot, pin, ring: null,
      color, baseHeight: ground, lon, lat, bottom, stackIndex,
      labelOffsetX: 0, labelOffsetY: 0, labelLevel: 0,
    }
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
    const top = Cesium.Cartesian3.fromDegrees(lon, lat, height + HUG_M)
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

  /**
   * 测点**位置**变了要整根重画（2026-09-23 修掉一个真事故）。
   *
   * 症状：演示场景里"测点跑到别的城市去了"，而且怎么刷新都一样。
   * 根因：`sync()` 对**已存在**的 handle 只更新了文字与颜色（`paint`），
   * 从没更新过几何——`lon/lat/bottom/baseHeight` 都是 `drawItem` 那一刻捕获的。
   * 而预览摆位（`public/devices.json`）是**后到的**：首屏先用档案坐标把点画出来，
   * 配置到位后 `displayPoints` 变了、`sync()` 也跑了，可坐标一直停在档案值上。
   * 立柱/引线高度那条 postRender 循环同样按旧 lon/lat 算，于是整片标点留在原场址。
   *
   * 这里显式比一次：位置或地面高度变了就重建这几段几何，让"数据变了视图就跟着变"
   * 这条约定在**原地更新**路径上也成立（以前只有新增/删除两条路径是完整的）。
   */
  function relocate(handle, item) {
    const lon = Number(item.longitude)
    const lat = Number(item.latitude)
    const ground = Number(item.altitude) || 0
    if (!Number.isFinite(lon) || !Number.isFinite(lat)) return
    if (handle.lon === lon && handle.lat === lat && handle.baseHeight === ground) return
    handle.lon = lon
    handle.lat = lat
    handle.baseHeight = ground
    const bottom = Cesium.Cartesian3.fromDegrees(lon, lat, ground)
    const top = Cesium.Cartesian3.fromDegrees(lon, lat, ground + HUG_M)
    handle.bottom = bottom
    handle.mast.polyline.positions = [bottom, top]
    handle.dot.position = new Cesium.ConstantPositionProperty(top)
    if (handle.pin) handle.pin.position = top
    if (handle.ring) {
      handle.ring.position = new Cesium.ConstantPositionProperty(
        Cesium.Cartesian3.fromDegrees(lon, lat, ground + 1),
      )
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
        /*
         * 档位数 4 → 8（2026-09-23）：一个演示场址里 7 个测点会被分到 0,1,2,3 里循环，
         * 于是第 5 个和第 1 个共用同一档、屏幕上一拉远就叠在一起。8 档让 7 个点各占一档，
         * 而 1000 点这种规模仍然是 8 档循环（不会出现一个标签被顶到两万像素外）。
         */
        const stackIndex = groupIndex % 8
        stackCounter[group] = groupIndex + 1
        const existing = handles.get(item.id)
        if (existing) {
          existing.item = item
          paint(existing, item)
          relocate(existing, item)
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
        /*
         * pin 必须一起删（2026-09-23 修）：漏掉它的后果不是"留个漂浮的水滴"这么轻——
         * 切到别的分区再切回来时，`drawItem` 会用同一个 id `pin-<id>` 再 add 一次，
         * Cesium 直接抛 DeveloperError，而异常发生在 watch 里，**整个 sync 中断**，
         * 那一批测点全部不同步（表现为"点了分区之后有的点不见了/位置不对"）。
         */
        if (handle.pin) viewer.entities.remove(handle.pin)
        if (handle.ring) viewer.entities.remove(handle.ring)
        handles.delete(id)
      }
    },

    /** 取某个点当前的显示口径（侧栏、图例复用） */
    visualOf(pointId) {
      return handles.get(pointId)?.visual || resolvePointVisual({})
    },

    /**
     * 标签避让的诊断快照（给无头浏览器验证用）：
     * `levels` 是每个点当前的让位级数，挤在一起时应该出现 0/1/2… 的阶梯，
     * 而不是所有点都是同一个值（那就说明避让没生效，或者还停在旧的全局档位逻辑上）。
     */
    labelDiag() {
      const levels = []
      for (const [id, handle] of handles) {
        if (handle.labelLevel === undefined) continue
        levels.push({
          id,
          level: handle.labelLevel,
          offsetY: handle.dot.label.pixelOffset?.y ?? null,
          rect: handle.labelRect ?? null,
          text: readLabelText(handle.dot.label.text, viewer.clock.currentTime),
        })
      }
      levels.sort((a, b) => Number(a.id) - Number(b.id))
      return { handles: handles.size, lastLayoutCount: lastLabelLayoutCount, levels }
    },

    /**
     * 位移引线：给屏幕空间叠加层用的线段清单。
     * `world` 是引线起点（点位的水滴尖端），`dx/dy` 是标签相对它的像素位移，
     * 叠加层每帧把 `world` 投影成屏幕坐标、再画一条到 `(x+dx, y+dy)` 的细线。
     * 没被挪开的标签不返回（近景不该多出一堆短线）。
     */
    leaderSegments() {
      const out = []
      for (const handle of handles.values()) {
        if (!handle.labelAnchor) continue
        const displaced =
          handle.labelLevel > 0 ||
          Math.abs(handle.labelOffsetX) > 0.5 ||
          Math.abs(handle.labelOffsetY + LABEL_GAP_PX) > 2
        if (!displaced) continue
        out.push({
          world: handle.labelAnchor,
          dx: handle.labelOffsetX,
          dy: handle.labelOffsetY,
          color: handle.visual?.color
            ? `${handle.visual.color}99`
            : 'rgba(255,255,255,0.55)',
        })
      }
      return out
    },

    applyTerrainHeights,

    /** 场景配置可按项目收紧标签距离；1000 点时远景不绘制全部文字。 */
    setLabelDistance(distance) {
      const value = Number(distance)
      if (!Number.isFinite(value) || value < 10) return
      // 这是"档案里的基准值"，实际生效的上界由 postRender 里的自适应逻辑取 max(它, 相机高度×系数)
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
