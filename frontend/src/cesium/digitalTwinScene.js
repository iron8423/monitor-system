import * as Cesium from 'cesium'

const DEFAULT_CAMERA = Object.freeze({ heading: 0, pitch: -35, range: 1000 })

function finite(value, fallback = 0) {
  const n = Number(value)
  return Number.isFinite(n) ? n : fallback
}

function requiredFinite(value, name) {
  const n = Number(value)
  if (!Number.isFinite(n)) throw new Error(`数字孪生配置缺少有效的 ${name}`)
  return n
}

function validateConfig(input) {
  if (!input?.enabled) throw new Error('数字孪生场景未启用')
  const assetType = String(input.assetType || '').toUpperCase()
  const coordinateMode = String(input.coordinateMode || '').toUpperCase()
  if (!['GLB', '3D_TILES'].includes(assetType)) {
    throw new Error(`不支持的三维资产类型：${input.assetType || '空'}`)
  }
  if (!['ENU', 'EMBEDDED'].includes(coordinateMode)) {
    throw new Error(`不支持的坐标模式：${input.coordinateMode || '空'}`)
  }
  if (!input.assetUrl) throw new Error('数字孪生配置缺少 assetUrl')
  if (coordinateMode === 'ENU') {
    requiredFinite(input.anchorLongitude, 'anchorLongitude')
    requiredFinite(input.anchorLatitude, 'anchorLatitude')
  }
  return { ...input, assetType, coordinateMode }
}

/** ENU 场景把模型本地 +X/+Y/+Z 对齐为东/北/上，并叠加姿态和统一比例。 */
function modelMatrixOf(config) {
  if (config.coordinateMode === 'EMBEDDED') return Cesium.Matrix4.clone(Cesium.Matrix4.IDENTITY)
  const anchor = Cesium.Cartesian3.fromDegrees(
    requiredFinite(config.anchorLongitude, 'anchorLongitude'),
    requiredFinite(config.anchorLatitude, 'anchorLatitude'),
    finite(config.anchorHeight),
  )
  const hpr = new Cesium.HeadingPitchRoll(
    Cesium.Math.toRadians(finite(config.headingDegrees)),
    Cesium.Math.toRadians(finite(config.pitchDegrees)),
    Cesium.Math.toRadians(finite(config.rollDegrees)),
  )
  const matrix = Cesium.Transforms.headingPitchRollToFixedFrame(
    anchor,
    hpr,
    Cesium.Ellipsoid.WGS84,
    Cesium.Transforms.eastNorthUpToFixedFrame,
  )
  return Cesium.Matrix4.multiplyByUniformScale(
    matrix,
    Math.max(0.000001, finite(config.modelScale, 1)),
    new Cesium.Matrix4(),
  )
}

async function loadAsset(viewer, config, modelMatrix) {
  if (config.assetType === 'GLB') {
    const model = await Cesium.Model.fromGltfAsync({
      url: config.assetUrl,
      modelMatrix,
      upAxis: Cesium.Axis.Z,
      forwardAxis: Cesium.Axis.X,
      allowPicking: false,
      shadows: Cesium.ShadowMode.ENABLED,
    })
    viewer.scene.primitives.add(model)
    return model
  }

  const memoryBytes = Math.max(64, finite(config.maximumMemoryMb, 512)) * 1024 * 1024
  const tileset = await Cesium.Cesium3DTileset.fromUrl(config.assetUrl, {
    maximumScreenSpaceError: Math.max(1, finite(config.maximumScreenError, 16)),
    cacheBytes: memoryBytes,
    maximumCacheOverflowBytes: Math.floor(memoryBytes * 0.25),
    cullWithChildrenBounds: true,
    skipLevelOfDetail: true,
    preferLeaves: false,
  })
  if (config.coordinateMode === 'ENU') tileset.modelMatrix = modelMatrix
  viewer.scene.primitives.add(tileset)
  return tileset
}

function radarFrame(radar) {
  const lon = Number(radar.longitude)
  const lat = Number(radar.latitude)
  if (!Number.isFinite(lon) || !Number.isFinite(lat)) return null
  const anchor = Cesium.Cartesian3.fromDegrees(lon, lat, finite(radar.altitude))
  return Cesium.Transforms.eastNorthUpToFixedFrame(anchor)
}

function localToWorld(frame, point) {
  return Cesium.Matrix4.multiplyByPoint(
    frame,
    new Cesium.Cartesian3(point[0], point[1], point[2]),
    new Cesium.Cartesian3(),
  )
}

/** 雷达姿态来自设备档案；缺少坐标的设备仍参与业务，但不会被错误画在 (0,0)。 */
/**
 * 每台雷达一个**固定色**，按声明顺序轮取。
 *
 * 为什么不能都用品红/青这种「状态色」：扇形面是重叠的（现场两台雷达的视场本就交叠），
 * 全用同一个颜色时，屏幕上只是几片同色半透明面叠在一起——分不出哪片是谁的，
 * 也就无法回答「这个测点归哪台雷达看」。状态（在线/离线）已经由雷达头与目标连线表达，
 * 覆盖面这一层要让位给「归属」。
 */
const RADAR_COLORS = ['#4ea8ff', '#ffb454', '#5ce1a6', '#b98cff']

export function radarColorOf(index) {
  return RADAR_COLORS[Math.abs(index) % RADAR_COLORS.length]
}

function addRadar(viewer, radar, index = 0, token = 'dt-') {
  const frame = radarFrame(radar)
  if (!frame) return { entities: [], targetEntities: [] }
  const id = radar.deviceId ?? radar.code
  const entities = []
  const targetEntities = []
  const add = (suffix, options) => {
    // token 让**每一次建场景**用一套独立的 id（见 createDigitalTwinScene 里 sceneToken 的说明）
    const entityId = `${token}radar-${id}-${suffix}`
    // 同 id 已存在时先摘掉再挂：创建流程可能被并发触发两次（初始化 + 项目变化），
    // 而两边都会 await 加载资产——只在函数开头清一次挡不住这种交错（实测仍会抛
    // "An entity with id ... already exists"）。这里逐个 id 幂等化，谁后建谁生效。
    const existing = viewer.entities.getById(entityId)
    if (existing) viewer.entities.remove(existing)
    const entity = viewer.entities.add({ id: entityId, ...options })
    entities.push(entity)
    return entity
  }

  add('platform', {
    position: localToWorld(frame, [0, 0, 0.8]),
    cylinder: {
      length: 1.6,
      topRadius: 3.2,
      bottomRadius: 3.8,
      material: Cesium.Color.fromCssColorString('#526171'),
      outline: true,
      outlineColor: Cesium.Color.fromCssColorString('#91a6b8'),
    },
    properties: { kind: 'radar', deviceId: radar.deviceId },
  })
  add('mast', {
    position: localToWorld(frame, [0, 0, 4.9]),
    cylinder: {
      length: 6.6,
      topRadius: 0.35,
      bottomRadius: 0.55,
      material: Cesium.Color.fromCssColorString('#b5c0cb'),
    },
    properties: { kind: 'radar', deviceId: radar.deviceId },
  })

  const statusColor = radar.status === 'ONLINE' ? '#35c9ff' : '#8a94a6'
  const faceColor = radarColorOf(index)
  const headHeight = Math.max(1, finite(radar.antennaHeightM, 8.8))
  add('head', {
    position: localToWorld(frame, [0, 0, headHeight]),
    ellipsoid: {
      radii: new Cesium.Cartesian3(1.5, 1.5, 1.1),
      material: Cesium.Color.fromCssColorString('#d7e2ea'),
      outline: true,
      outlineColor: Cesium.Color.fromCssColorString(statusColor),
    },
    properties: { kind: 'radar', deviceId: radar.deviceId },
  })
  /*
   * 雷达名牌（2026-09-23 用户反馈"雷达标签飞在竖线没有接地、不随缩放自适应"）。
   *
   * 以前名牌是挂在**天线头**上的一个 label：位置固定在离地 8.8 m，
   * 于是它永远悬在半空——天线柱只从 1.6 m 到 8.2 m，柱脚离地还有一截，
   * 看上去就是"一块牌子飘在竖线上方"。而且字号是死值，拉远拉近一个样。
   *
   * 现在把名牌拆成"**贴地引线 + 引线顶端的名牌**"两件，和测点是同一套做法：
   *   · 引线起点 = 雷达脚下的地面（z=0.15 m，确保真的接上地）；
   *   · 引线终点 = 名牌锚点，高度跟着**相机离地高度**走（见 updateRadarLabels）；
   *   · 名牌字号也按相机高度线性插值，缩到最远仍然读得出名字。
   */
  const labelAnchor = localToWorld(frame, [0, 0, headHeight + 6])
  const labelGround = localToWorld(frame, [0, 0, 0.15])
  const leader = add('label-leader', {
    polyline: {
      positions: [labelGround, labelAnchor],
      width: 1.6,
      material: Cesium.Color.fromCssColorString(faceColor).withAlpha(0.7),
    },
    properties: { kind: 'radar', deviceId: radar.deviceId },
  })
  const label = add('label', {
    position: labelAnchor,
    label: {
      text: radar.name || radar.code || '雷达',
      font: '14px "Microsoft YaHei", sans-serif',
      fillColor: Cesium.Color.WHITE,
      showBackground: true,
      backgroundColor: Cesium.Color.fromCssColorString('#06101f').withAlpha(0.82),
      backgroundPadding: new Cesium.Cartesian2(8, 4),
      pixelOffset: new Cesium.Cartesian2(0, -4),
      verticalOrigin: Cesium.VerticalOrigin.BOTTOM,
      // 名牌也是点击目标（和雷达头一样能选中这台雷达）
      disableDepthTestDistance: Number.POSITIVE_INFINITY,
    },
    properties: { kind: 'radar', deviceId: radar.deviceId },
  })

  const heading = Cesium.Math.toRadians(finite(radar.headingDegrees))
  const pitch = Cesium.Math.toRadians(finite(radar.pitchDegrees))
  const half = Cesium.Math.toRadians(Math.max(0.1, finite(radar.halfAngleDegrees, 25)))
  const verticalHalf = Cesium.Math.toRadians(
    Math.max(0.1, finite(radar.verticalHalfAngleDegrees, 15)),
  )
  const range = Math.max(1, finite(radar.detectionRangeM, 250))
  const start = localToWorld(frame, [0, 0, headHeight])
  const fan = [start]
  const arc = []
  const upperArc = []
  const lowerArc = []
  const samples = 24
  for (let i = 0; i <= samples; i += 1) {
    const angle = heading - half + half * 2 * (i / samples)
    const horizontal = Math.cos(pitch) * range
    const end = localToWorld(frame, [
      Math.sin(angle) * horizontal,
      Math.cos(angle) * horizontal,
      headHeight + Math.sin(pitch) * range,
    ])
    fan.push(end)
    arc.push(end)
    for (const [elevation, target] of [
      [pitch + verticalHalf, upperArc],
      [pitch - verticalHalf, lowerArc],
    ]) {
      const horizontalAtElevation = Math.cos(elevation) * range
      target.push(localToWorld(frame, [
        Math.sin(angle) * horizontalAtElevation,
        Math.cos(angle) * horizontalAtElevation,
        headHeight + Math.sin(elevation) * range,
      ]))
    }
  }
  /*
   * 视场扇面 = **理论视场**（复查清单 P1-11）。
   *
   * 它是按量程/水平半角/俯仰角**解析算出来**的：不打地形、不裁剪、不考虑山体与建筑。
   * 所以它回答的是"这台雷达按参数覆盖到哪一片"，而不是"这一片真的看得到"。
   * 界面上的表达必须让这两件事一眼可分：
   *   · 面：极低 alpha（0.07）——它是提示层，不是结论；
   *   · 轮廓：**虚线**——上一版是实线，看起来像一条已经成立的边界；
   *   · 措辞：面板与图例都写"理论视场"，并在旁注里写明"未按地形裁剪"。
   * 真正的"看得到"由目标连线表达：只有 ACTIVE + 程序化通视校验通过的目标才是实线。
   *
   * **默认只画水平扇面这一片**（2026-09-20 用户反馈"为什么有三个区域"）：
   * 上一版每台雷达同时画出扇面 + 垂直上边界楔形 + 下边界楔形，三片轮廓叠在一起，
   * 看上去像三块不同区域，而它们其实只是同一台雷达同一个视场在水平、垂直两个方向上的边界。
   * 现在垂直边界默认隐藏，**选中那一台时才出现**——需要细节时点一下，平时一眼就是"一台雷达一片"。
   */
  const sectorFace = add('sector', {
    polygon: {
      hierarchy: new Cesium.PolygonHierarchy(fan),
      perPositionHeight: true,
      material: Cesium.Color.fromCssColorString(faceColor).withAlpha(0.07),
      outline: false,
    },
    properties: { kind: 'radar-decoration', deviceId: radar.deviceId },
  })
  /*
   * 地形裁剪覆盖（P1-11 后半）。几何来自后端 `/devices/{id}/coverage`：
   * 每条方位线给一个"连续可见距离"，把它们的端点连起来，扇面外缘就跟着山脊线走。
   *
   * 与理论扇面的分工写在面板与图例里：理论视场是虚线提示层（按参数解析算的），
   * 这一层是**按地形算出来的**（同一份高程场、与标定校核同一套判据）。
   * 后端若没有该场景的高程场，radar.coverage 会是 undefined —— 这时**什么都不画**，
   * 而不是退回理论扇面冒充。少一层比多一层假结论好。
   */
  const coverage = radar.coverage
  let coverageFace = null
  let coverageOutline = null
  if (coverage?.terrainAvailable && Array.isArray(coverage.rays) && coverage.rays.length >= 2) {
    const radarGround = finite(radar.altitude)
    const boundary = coverage.rays.map((ray) => {
      const a = Cesium.Math.toRadians(finite(ray.azimuthDegrees))
      const d = Math.max(0, finite(ray.visibleDistanceM))
      // 本地 z：边界处地面高程 - 雷达处地面高程（frame 的原点就是雷达所在地面）
      const z = finite(ray.groundAltitudeM) - radarGround
      return localToWorld(frame, [Math.sin(a) * d, Math.cos(a) * d, z + 0.4])
    })
    coverageFace = add('coverage', {
      polygon: {
        hierarchy: new Cesium.PolygonHierarchy([start, ...boundary]),
        perPositionHeight: true,
        material: Cesium.Color.fromCssColorString(faceColor).withAlpha(0.18),
        outline: false,
      },
      properties: { kind: 'radar-decoration', deviceId: radar.deviceId },
    })
    coverageOutline = add('coverage-outline', {
      polyline: {
        positions: [start, ...boundary, start],
        width: 1.6,
        material: Cesium.Color.fromCssColorString(faceColor).withAlpha(0.9),
      },
      properties: { kind: 'radar-decoration', deviceId: radar.deviceId },
    })
  }

  const sectorOutline = add('sector-outline', {
    polyline: {
      positions: [start, ...arc, start],
      width: 1.2,
      material: new Cesium.PolylineDashMaterialProperty({
        color: Cesium.Color.fromCssColorString(faceColor).withAlpha(0.55),
        dashLength: 14,
      }),
    },
    properties: { kind: 'radar-decoration', deviceId: radar.deviceId },
  })
  // 上下边界共同表达垂直视场；中心扇面保留为方向提示，但不再冒充完整覆盖体。
  const sectorUpper = add('frustum-upper-outline', {
    polyline: {
      positions: [start, ...upperArc, start],
      width: 1.0,
      material: new Cesium.PolylineDashMaterialProperty({
        color: Cesium.Color.fromCssColorString(faceColor).withAlpha(0.3),
        dashLength: 10,
      }),
    },
    show: false,   // 默认隐藏：见上面"每台只画一片"的说明
    properties: { kind: 'radar-decoration', deviceId: radar.deviceId },
  })
  const sectorLower = add('frustum-lower-outline', {
    polyline: {
      positions: [start, ...lowerArc, start],
      width: 1.0,
      material: new Cesium.PolylineDashMaterialProperty({
        color: Cesium.Color.fromCssColorString(faceColor).withAlpha(0.3),
        dashLength: 10,
      }),
    },
    show: false,   // 默认隐藏：同上
    properties: { kind: 'radar-decoration', deviceId: radar.deviceId },
  })

  /*
   * 目标连线 = **这座雷达对每个目标的核验状态**，三档各有各的说法（P1-11）：
   *   · ACTIVE + 通视通过 → 实线绿：已核验（标定有效、视线成立）；
   *   · 通视通过但标定不是 ACTIVE → 虚线黄：待核验（能看到，但没走完标定流程）；
   *   · 程序化通视判定被遮挡 → 点线红：被遮挡（贴着屏幕的实话是"现在看不到"）。
   * 上一版三档全是虚线、只差线宽，扫一眼分不出"已核验"与"待核验"——
   * 而这两者的处置动作完全不同（一个可以直接采信，一个要先去标定）。
   */
  for (const target of radar.targets || []) {
    const lon = Number(target.longitude)
    const lat = Number(target.latitude)
    if (!Number.isFinite(lon) || !Number.isFinite(lat)) continue
    const altitude = finite(target.altitude) + Math.max(0, finite(target.reflectorHeightM, 0))
    const end = Cesium.Cartesian3.fromDegrees(lon, lat, altitude)
    const active = String(target.calibrationStatus || '').toUpperCase() === 'ACTIVE'
    const visible = target.lineOfSight === true
    const verified = active && visible
    const color = verified ? '#55e6a5' : visible ? '#f4c95d' : '#ff6b6b'
    const line = add(`target-${target.bindingId ?? target.pointId}`, {
      show: false,
      polyline: {
        positions: [start, end],
        width: verified ? 2.4 : 1.6,
        // 已核验走实线（它是结论）；其余两档走虚线/点线（它们是待办与事实）。
        material: verified
          ? Cesium.Color.fromCssColorString(color).withAlpha(0.9)
          : new Cesium.PolylineDashMaterialProperty({
            color: Cesium.Color.fromCssColorString(color).withAlpha(0.8),
            dashLength: visible ? 12 : 5,
          }),
      },
      properties: {
        kind: 'radar-target',
        deviceId: radar.deviceId,
        pointId: target.pointId,
        bindingId: target.bindingId,
      },
    })
    targetEntities.push(line)
  }
  // sector* 一并返回：选中雷达时要能**只把那一台**的覆盖面提亮（见 setActiveRadar），
  // 图层开关也要能整体隐藏（见 setSectorVisible）——只靠 `decorations` 拿不到这几片。
  return {
    entities,
    targetEntities,
    faceColor,
    // 名牌句柄：位置/字号由 createDigitalTwinScene 的相机自适应循环统一更新
    labelHandle: { label, leader, frame, headHeight, ground: labelGround },
    sector: { face: sectorFace, outline: sectorOutline, upper: sectorUpper, lower: sectorLower },
    coverage: { face: coverageFace, outline: coverageOutline },
  }
}

function cameraOf(config) {
  const fitRange = fitRangeFor(sceneRadius, sceneViewer)
  const pitchOverride = parseFloat(import.meta.env.VITE_CAMERA_PITCH)
  return {
    heading: finite(config.cameraHeadingDegrees, DEFAULT_CAMERA.heading),
    pitch: Number.isFinite(pitchOverride)
      ? pitchOverride
      : finite(config.cameraPitchDegrees, DEFAULT_CAMERA.pitch),
    range: fitRange,
  }
}

/*
 * 取景（2026-09-21 第四版）：**以模型真实包围球为准**，不再依赖配置里的尺寸字段
 * （上一版就是因为后端配置里没有 dimensionsMetres，退回 1000×750 默认值，距离全错）。
 * 包围球由 Cesium 在模型加载后自己算出，模型多大它就多大。
 */
let sceneRadius = 1500
let sceneViewer = null
let cameraClampHandler = null
/** 场景实例自增号：用于给每个实例的实体 id 加前缀，避免并发建场景时互相删实体 */
let sceneToken = 0
/*
 * 相机限制器总开关（2026-09-23）：切换项目/场景时相机会从旧场址飞到新场址，
 * 而限制器是**按新场址锚点**每帧把相机拽回来的——飞行中途被它拽一下就会被 setView 打断，
 * 相机停在朝向旧场址的方向上，看到的是一片黑。所以飞行期间必须临时关掉它。
 */
let clampEnabled = true
export function setClampEnabled(enabled) {
  clampEnabled = Boolean(enabled)
}
/**
 * 正在进行的相机飞行数（2026-09-23 第二轮修）：
 * 用布尔量会被"两次切换叠加"打穿——第一次飞行的完成回调把限制器打开时，
 * 第二次还在飞，于是中途被拽走（用户看到"跑到旧的地方去"）。改成计数，只有全部飞完才恢复。
 */
let flightsInProgress = 0
function beginFlight() {
  flightsInProgress += 1
  clampEnabled = false
}
function endFlight() {
  flightsInProgress = Math.max(0, flightsInProgress - 1)
  if (flightsInProgress === 0) clampEnabled = true
}
/** 最近一次场景的锚点（ENU 原点），供"回到全局视角"在不依赖旧 scene 实例的情况下复用 */
export let lastSceneAnchor = null
export let lastSceneHeading = 0
export let lastScenePitch = -38

/**
 * 从**当前三维场景里真实加载的那个模型**量半径。
 * 为什么不只依赖建场景时缓存的值：Vite 热更新会保留"上一次创建的场景实例"，
 * 旧实例里的闭包还是旧代码——用户不整页刷新时，按钮会走到旧逻辑上。
 * 这个函数每次现算，谁的代码新都无所谓。
 */
function radiusFromScene(viewer) {
  const prims = viewer?.scene?.primitives
  if (prims) {
    for (let i = 0; i < prims.length; i += 1) {
      const p = prims.get(i)
      // 未就绪/已销毁的 Model 读 boundingSphere 会**抛异常**（见下面 createDigitalTwinScene 的说明），
      // 这里是"回到全局视角/切分区"都会走的路径，抛出去就是按钮点了没反应。
      try {
        const r = Number(p?.boundingSphere?.radius)
        if (Number.isFinite(r) && r > 0) return { radius: r, measured: true }
      } catch { /* 换下一个图元 */ }
    }
  }
  return { radius: sceneRadius, measured: false }
}

/**
 * 把相机放到"刚好装下模型"的位置 —— **与场景实例无关**的公共入口。
 * 大屏的"回到全局视角"按钮直接调它，因此即使页面没有整页刷新、旧场景实例还在，
 * 取景逻辑也用的是最新代码。返回一组数字供界面诊断显示。
 */
export function fitSceneView(viewer, { duration = 0 } = {}) {
  if (!viewer) return null
  const { radius, measured } = radiusFromScene(viewer)
  sceneViewer = viewer
  sceneRadius = Math.max(100, radius)
  const anchor =
    lastSceneAnchor ||
    Cesium.Cartesian3.fromDegrees(113.541523, 24.4162209, 0)
  const range = fitRangeFor(sceneRadius, viewer)
  applyFitView(viewer, anchor, lastSceneHeading, lastScenePitch, duration)
  const info = {
    radius: Math.round(sceneRadius),
    radiusMeasured: measured,
    lastRange: Math.round(range),
  }
  // 俯仰角兜底也在这里装（幂等）：只改姿态、不动位置，见下面的说明。
  // 「看不到虚空」不依赖任何创建场景时的代码路径。
  if (!viewer.__fitClampInstalled) {
    viewer.__fitClampInstalled = true
    let lastClampAt = 0
    viewer.scene.postRender.addEventListener(() => {
      /*
       * 方案 A 之后这里只剩一件事：**俯仰角兜底**。
       *
       * 距离与横移都由 lookAt 变换 + 控制器限制管住了（不需要每帧修相机，
       * 也就不会再有"点列表闪烁变黑"）。但 Cesium 的 tilt 没有上下限，
       * 拉到贴地平线时地形会变成一条线、上方全是天空——这不是"虚空"，
       * 但仍然不好看，所以只在**明显超出**时纠正一次，且 400 ms 内只修一次。
       */
      const pitchDeg = Cesium.Math.toDegrees(viewer.camera.pitch)
      const minPitch = parseFloat(import.meta.env.VITE_CAMERA_MIN_PITCH) || -78
      const maxPitch = parseFloat(import.meta.env.VITE_CAMERA_MAX_PITCH) || -18
      const now = Date.now()
      if ((pitchDeg > maxPitch || pitchDeg < minPitch) && now - lastClampAt > 400) {
        lastClampAt = now
        const clamped = Math.min(Math.max(pitchDeg, minPitch), maxPitch)
        /*
         * 只改姿态、**不动位置**（2026-09-23 用户反馈"旋转受限"）。
         *
         * 以前这里用 `lookAt(锚点, HPR(heading, clamped, range))`：它会把相机重新按
         * "到锚点的距离 = range"摆一遍。虽然 range 取的是当前距离，看着没变，但在
         * lookAt 坐标系里连续拖动时，每 400 ms 被这样"重摆"一次，手感就是**转到一个角度
         * 就被弹一下、越拖越粘**——那正是用户说的"旋转受限"。
         * `setView({ orientation })` 不带 destination 时只改朝向，位置原样保留。
         */
        viewer.camera.setView({
          orientation: {
            heading: viewer.camera.heading,
            pitch: Cesium.Math.toRadians(clamped),
            roll: 0,
          },
        })
      }
    })
  }
  if (typeof window !== 'undefined') window.__sceneFit = { ...(window.__sceneFit || {}), ...info }
  return info
}

function fitRangeFor(radius, viewer) {
  if (!viewer) return radius * 2.2
  const canvas = viewer.canvas
  const aspect = canvas && canvas.clientHeight ? canvas.clientWidth / canvas.clientHeight : 16 / 9
  const frustum = viewer.camera.frustum
  // Cesium 的 `fov` 是"宽>高时按水平、否则按垂直"解释的，`fovy` 才是垂直。
  // 之前直接用 fovy → 对宽屏来说垂直视场只有 36° 左右，算出的距离偏大 2 倍多。
  // 这里取**两者较小的那个**作为约束，再乘一个"平面模型修正系数"。
  const fovy = frustum.fovy || Cesium.Math.toRadians(60)
  const hfov = 2 * Math.atan(Math.tan(fovy / 2) * aspect)
  const half = Math.min(fovy, hfov) / 2
  // 包围球是按"三维对角"算的（含山高），而地形是**扁平**的：按球取景会把画面浪费一倍。
  // 默认按 0.55 折算（实测 3000×2250 场景约填满 75~85% 画面），可环境变量覆盖。
  const effFactor = parseFloat(import.meta.env.VITE_CAMERA_FIT_RADIUS_FACTOR) || 0.55
  const effRadius = radius * effFactor
  const margin = parseFloat(import.meta.env.VITE_CAMERA_FIT_MARGIN) || 1.06
  /*
   * 取景距离上限（2026-09-22）：地块外扩到 6km 后，"刚好装下整块"要把相机推到 5km 之外，
   * 用户反馈"相机拉太远"。远景层铺上真实影像之后，没必要非把整块地块塞进画面——
   * 直接给一个上限（默认 2800m），默认视角就落在厂区核心区，四周由远景层自然延续。
   */
  const maxRange = parseFloat(import.meta.env.VITE_CAMERA_FIT_MAX_RANGE) || 0
  const fitted = (effRadius / Math.sin(half)) * margin
  return maxRange > 0 ? Math.min(fitted, maxRange) : fitted
}

/*
 * 把相机放到"刚好装下模型"的位置（2026-09-21 第五版）。
 *
 * 为什么不再用 flyToBoundingSphere：它对 `offset.range` 的解释依赖包围球，
 * 我们既传过球半径又传过距离，两者叠加后距离翻倍——用户看到的就是"点了全局视角变成一个小点"。
 * 现在改成：`camera.lookAt(锚点, HeadingPitchRange(heading, pitch, 距离))` ——
 * **距离就是距离**，语义唯一；再把姿态取出来、用 flyTo 做动画。
 */
function applyFitView(viewer, anchor, headingDeg, pitchDeg, duration = 0) {
  const range = fitRangeFor(sceneRadius, viewer)
  if (typeof window !== 'undefined') {
    window.__sceneFit = {
      ...(window.__sceneFit || {}),
      lastRange: Math.round(range),
      lastHeading: headingDeg,
      lastPitch: pitchDeg,
    }
  }
  /*
   * 锁定轨道（2026-09-21 方案 A）：把相机**留在以场址为原点的 lookAt 变换里**，
   * 不再 lookAtTransform(IDENTITY)。
   *
   * 为什么这是关键：Cesium 的默认交互是"绕地球"的——
   *   旋转 = 绕地心转、平移 = 在地表滑、缩放 = 改到椭球的距离。
   * 相机待在变换里之后，这三件事全部变成"绕场址"：
   *   旋转 → 永远绕场址转，模型不会被甩出画面；
   *   缩放 → 距离从场址量起，上限就是"刚好装下"；
   *   平移 → 直接禁用（铺满状态下平移必然露边）。
   * 全部限制都变成"本地米"，不用再跟地球半径较劲。
   */
  const headingRad = Cesium.Math.toRadians(headingDeg)
  const pitchRad = Cesium.Math.toRadians(pitchDeg)
  if (duration > 0) {
    // 有动画时走 flyTo；飞完再回到"场址 lookAt 变换"，否则之后的旋转会绕地球转（老问题）
    beginFlight()   // 飞行期间别让限制器（按新锚点）把相机拽回来打断飞行
    animateLookAt(viewer, anchor, headingDeg, pitchDeg, range, duration).then((done) => {
      endFlight()
      if (done) attachCameraLimits(viewer, anchor, range)
    })
  } else {
    viewer.camera.lookAtTransform(Cesium.Matrix4.IDENTITY)
    viewer.camera.lookAt(
      anchor,
      new Cesium.HeadingPitchRange(headingRad, pitchRad, range),
    )
  }
  attachCameraLimits(viewer, anchor, range)
}

/**
 * 平滑地把相机送到"目标点 + 给定朝向/俯角/距离"（2026-09-23 用户要求切换有动画）。
 *
 * 做法：先用 ENU 数学算出目标相机位置（Cesium 的 flyTo 里 heading/pitch/roll 是相对
 * **目的地的 ENU 系**解释的，所以朝向直接用同一组角度即可），飞完之后再用 `lookAt`
 * 把相机重新锚回场址坐标系——两处姿态完全一致，所以看不出跳变，但保住了"绕场址旋转"。
 */
export function animateLookAt(viewer, target, headingDeg, pitchDeg, rangeM, duration = 1.6) {
  if (!viewer || !target) return Promise.resolve(false)
  const heading = Cesium.Math.toRadians(headingDeg)
  const pitch = Cesium.Math.toRadians(pitchDeg)
  const frame = Cesium.Transforms.eastNorthUpToFixedFrame(target)
  const direction = new Cesium.Cartesian3(
    Math.sin(heading) * Math.cos(pitch),
    Math.cos(heading) * Math.cos(pitch),
    Math.sin(pitch),
  )
  const offset = Cesium.Cartesian3.multiplyByScalar(direction, -rangeM, new Cesium.Cartesian3())
  const destination = Cesium.Matrix4.multiplyByPoint(frame, offset, new Cesium.Cartesian3())
  return new Promise((resolve) => {
    viewer.camera.flyTo({
      destination,
      orientation: { heading, pitch, roll: 0 },
      duration,
      complete: () => {
        viewer.camera.lookAt(target, new Cesium.HeadingPitchRange(heading, pitch, rangeM))
        resolve(true)
      },
      cancel: () => resolve(false),
    })
  })
}

/**
 * **直接平移**式取景（2026-09-23 用户要求："不要先向上再下来，直接平移过去就行"）。
 *
 * 为什么 `flyTo` 会"先升后降"：Cesium 的 flyTo 走的是航空式飞行路径（会抬升再落），
 * 场址内切换看着就像绕了一圈。这里改成在**世界坐标里对位置做直线插值**、
 * 朝向做角度插值——就是纯粹的平移+转向，适合几百米量级的场址内切换。
 * 大跨度（跨场址）仍应使用 animateLookAt 的飞行动画，否则会穿过地球。
 */
export function panLookAt(viewer, target, headingDeg, pitchDeg, rangeM, durationMs = 1100) {
  if (!viewer || !target) return Promise.resolve(false)
  const heading = Cesium.Math.toRadians(headingDeg)
  const pitch = Cesium.Math.toRadians(pitchDeg)
  const frame = Cesium.Transforms.eastNorthUpToFixedFrame(target)
  const direction = new Cesium.Cartesian3(
    Math.sin(heading) * Math.cos(pitch),
    Math.cos(heading) * Math.cos(pitch),
    Math.sin(pitch),
  )
  const offset = Cesium.Cartesian3.multiplyByScalar(direction, -rangeM, new Cesium.Cartesian3())
  const endPosition = Cesium.Matrix4.multiplyByPoint(frame, offset, new Cesium.Cartesian3())
  const startPosition = Cesium.Cartesian3.clone(viewer.camera.positionWC)
  const startHeading = viewer.camera.heading
  const startPitch = viewer.camera.pitch
  // 取最短转向路径（避免 350° → 10° 时反向绕一大圈）
  let deltaHeading = heading - startHeading
  while (deltaHeading > Math.PI) deltaHeading -= Math.PI * 2
  while (deltaHeading < -Math.PI) deltaHeading += Math.PI * 2
  const startedAt = performance.now()
  beginFlight()
  return new Promise((resolve) => {
    const step = () => {
      const t = Math.min(1, (performance.now() - startedAt) / durationMs)
      const k = t * t * (3 - 2 * t)   // smoothstep：起步和收尾都平顺
      const position = Cesium.Cartesian3.lerp(
        startPosition, endPosition, k, new Cesium.Cartesian3(),
      )
      viewer.camera.setView({
        destination: position,
        orientation: {
          heading: startHeading + deltaHeading * k,
          pitch: startPitch + (pitch - startPitch) * k,
          roll: 0,
        },
      })
      if (t < 1) {
        requestAnimationFrame(step)
        return
      }
      viewer.camera.lookAt(target, new Cesium.HeadingPitchRange(heading, pitch, rangeM))
      endFlight()
      resolve(true)
    }
    requestAnimationFrame(step)
  })
}

/**
 * 距离/平移/俯仰的三条限制（提取出来，动画与非动画两条路径共用）。
 *
 * 2026-09-23 用户反馈"缩放到最小/最大都有问题、旋转受限"，这里逐条对账：
 *   · 以前 `maximumZoomDistance = range`（=取景距离本身）→ **根本不能往外缩**，
 *     一滚轮就顶住；而全局视角按钮用的就是同一个距离，所以"缩放到最小"其实是"回到取景距离"。
 *     现在放宽到 `取景距离 × VITE_MAX_ZOOM_FACTOR`（默认 1.8），能再拉远看周边，
 *     因为远景层铺的是真实影像，拉远看到的仍然是地面而不是虚空。
 *   · 以前 `minimumZoomDistance = max(80, range×0.12)`（默认 125 m）→ **贴不近**，
 *     想看建筑立面、想有"身临其境"的感觉时推不进去。现在降到 `max(25, range×0.05)`（默认约 52 m）。
 *   · `enableTranslate = false` 保留：相机待在"绕场址"的 lookAt 坐标系里，
 *     平移就会滑到场址之外、露出地块边缘——那是用户明确不要的虚空。
 */
function attachCameraLimits(viewer, anchor, range) {
  const ctrl = viewer.scene.screenSpaceCameraController
  const zoomOutFactor = parseFloat(import.meta.env.VITE_MAX_ZOOM_FACTOR) || 1.8
  const zoomInFactor = parseFloat(import.meta.env.VITE_MIN_ZOOM_FACTOR) || 0.05
  ctrl.minimumZoomDistance = Math.max(25, range * zoomInFactor)
  ctrl.maximumZoomDistance = range * zoomOutFactor
  ctrl.enableTranslate = false
  ctrl.enableTilt = true
  ctrl.enableLook = true
  lastSceneAnchor = anchor
}

/**
 * 创建项目数字孪生场景。调用方负责处理项目切换竞态，并在不再使用时 destroy()。
 */
export async function createDigitalTwinScene(viewer, rawConfig) {
  const config = validateConfig(rawConfig)
  // 新场景开始：清掉上一次飞行留下的状态（否则限制器可能一直是关的，或计数残留）
  flightsInProgress = 0
  clampEnabled = true
  /*
   * 每个场景实例一套独立的实体 id 前缀（2026-09-23 修掉一个真事故）。
   *
   * 触发路径：首屏 `loadData()` 会把 projectId 写进 store（→ 触发项目 watch，建一次场景），
   * 紧接着 `initViewer()` 自己又会 `await loadProjectScene(...)`；WebGL 上下文恢复时
   * initViewer 还会再来一遍。同一时刻有两个 `createDigitalTwinScene` 在跑是常态。
   *
   * 两边以前都用 `dt-radar-1-label` 这种**同一个 id**，于是输的那一边在
   * `if (generation !== sceneLoadGeneration) { scene.destroy() }` 里按 id 删实体时，
   * 删掉的其实是**赢的那一边刚建好的实体**——雷达名牌、视场扇面、覆盖层全没了，
   * 页面上只剩模型和测点（"雷达标签不接地 / 雷达不见了"就是这么来的）。
   * 现在 id 带实例前缀：谁删谁自己的，删不到别人的。
   */
  sceneToken += 1
  const token = `dt${sceneToken}-`
  /*
   * 先清掉上一场的残留实体（2026-09-22 查到的"顶栏挂假故障"）：
   * loadProjectScene 会被并发触发两次（初始化 + 项目变化），第二次给同一台雷达
   * add 同 id 的实体时，Cesium 直接抛
   *   "An entity with id dt-radar-1-platform already exists in this collection"
   * 于是整场被判成"数字孪生加载失败"——可画面其实是好的，顶栏就一直挂着一条假故障。
   * 场景里的实体 id 统一以 `dt` 开头，这里按前缀清一遍，重复创建就变成幂等的。
   */
  for (const entity of viewer.entities.values.filter(
    (item) => String(item.id || '').startsWith('dt'),
  )) {
    viewer.entities.remove(entity)
  }
  const modelMatrix = modelMatrixOf(config)
  const asset = await loadAsset(viewer, config, modelMatrix)
  // 模型加载 ≠ 模型就绪：`boundingSphere` 要等 glTF 解析完才有值。
  // 之前直接在 await loadAsset 之后读它，很可能读到 undefined → 半径回退默认值 →
  // 取景距离跟着错（这正是"全局视角变成一个小点"的一种成因）。这里显式等就绪。
  /*
   * 等模型就绪：**用 readyEvent**（Cesium 1.145 的正规接口）。
   * 之前这里用的是 `asset.readyPromise` —— 那个属性在当前版本已经移除，
   * 访问它会抛异常，而异常发生在 loadAsset 之后 → 模型已经显示、但后面
   * "记半径 / 装相机边界 / 记诊断"全都执行不到（用户看到的正是"进去没取景、
   * 点按钮才生效"）。这里整段用 try 包住，任何情况都不再往外抛。
   */
  try {
    if (asset?.readyEvent?.addEventListener && !asset.ready) {
      await new Promise((resolve) => {
        let done = false
        const stop = asset.readyEvent.addEventListener(() => {
          if (!done) { done = true; resolve() }
        })
        setTimeout(() => {
          if (!done) { done = true; resolve() }
          try { stop && stop() } catch { /* 忽略 */ }
        }, 3000)
      })
    }
  } catch {
    /* 就绪等待失败也要把现场显示出来 */
  }
  // 以模型真实包围球为准记录场景半径，并**装一个相机限制器**：任何操作（缩放/平移/旋转）
  // 都不能把相机拉到"模型之外"——用户反馈"移动会移动到虚空里"，根因是 Cesium 的
  // maximumZoomDistance 约束的是"到椭球的距离"，与我们自己的模型毫无关系。
  sceneViewer = viewer
  /*
   * 读包围球要包 try（2026-09-23，航拍级资产暴露）：
   * `model.boundingSphere` 在模型**未就绪**时是**抛 DeveloperError**（"The model is not loaded"），
   * 不是返回 undefined —— 所以 `asset?.boundingSphere?.radius` 这种写法保护不了它。
   * 上面那段就绪等待最多只等 3 s；39 MB / 8192 贴图的重资产（软件渲染）到这里还没 ready，
   * 于是异常从建场景函数里抛出去，**后面取景、相机限制、诊断全都不执行**——画面里能看到模型，
   * 但顶部显示"数字孪生加载失败"，怎么点都不取景。轻资产一直没暴露这个问题。
   */
  let measuredRadius = NaN
  try {
    measuredRadius = Number(asset?.boundingSphere?.radius)
  } catch {
    /* 没就绪就先不量，用兜底半径；下面挂 readyEvent 补量 */
  }
  sceneRadius = Math.max(100, Number.isFinite(measuredRadius) && measuredRadius > 0 ? measuredRadius : 1500)
  if (typeof window !== 'undefined') {
    window.__sceneFit = {
      coordinateMode: config.coordinateMode,
      assetUrl: config.assetUrl,
      radius: sceneRadius,
      radiusMeasured: Number.isFinite(measuredRadius) && measuredRadius > 0,
    }
  }
  // 迟到的就绪也要把半径补上：取景距离按它算，量不到就会一直用 1500 的兜底值
  if (!(Number.isFinite(measuredRadius) && measuredRadius > 0) && asset?.readyEvent?.addEventListener) {
    try {
      asset.readyEvent.addEventListener(() => {
        try {
          const late = Number(asset.boundingSphere?.radius)
          if (!Number.isFinite(late) || late <= 0) return
          sceneRadius = Math.max(100, late)
          if (typeof window !== 'undefined' && window.__sceneFit) {
            window.__sceneFit.radius = sceneRadius
            window.__sceneFit.radiusMeasured = true
          }
        } catch { /* 仍然读不到就当没量到 */ }
      })
    } catch { /* 忽略 */ }
  }
  const anchorCartesian = Cesium.Cartesian3.fromDegrees(
    finite(config.anchorLongitude),
    finite(config.anchorLatitude),
    finite(config.anchorHeight),
  )
  // 记下锚点与朝向：供模块级 fitSceneView() 复用（不依赖本次创建的 scene 实例）
  lastSceneAnchor = anchorCartesian
  lastSceneHeading = finite(config.cameraHeadingDegrees, 315)
  lastScenePitch = Number.isFinite(parseFloat(import.meta.env.VITE_CAMERA_PITCH))
    ? parseFloat(import.meta.env.VITE_CAMERA_PITCH)
    : finite(config.cameraPitchDegrees, -38)
  const fitRange = fitRangeFor(sceneRadius, viewer)
  const maxDistance = fitRange * (parseFloat(import.meta.env.VITE_MAX_ZOOM_FACTOR) || 1.25)
  viewer.scene.screenSpaceCameraController.maximumZoomDistance = maxDistance * 2
  if (cameraClampHandler) viewer.scene.postRender.removeEventListener(cameraClampHandler)
  cameraClampHandler = () => {
    if (!clampEnabled) return
    const cam = viewer.camera.positionWC
    const d = Cesium.Cartesian3.distance(cam, anchorCartesian)
    if (d <= maxDistance) return
    const dir = Cesium.Cartesian3.normalize(
      Cesium.Cartesian3.subtract(cam, anchorCartesian, new Cesium.Cartesian3()),
      new Cesium.Cartesian3(),
    )
    const target = Cesium.Cartesian3.add(
      anchorCartesian,
      Cesium.Cartesian3.multiplyByScalar(dir, maxDistance, new Cesium.Cartesian3()),
      new Cesium.Cartesian3(),
    )
    viewer.camera.setView({
      destination: target,
      orientation: { direction: viewer.camera.directionWC, up: viewer.camera.upWC },
    })
  }
  viewer.scene.postRender.addEventListener(cameraClampHandler)
  const decorations = []
  const radarGroups = new Map()
  /** 雷达名牌的自适应句柄（见 addRadar 里的说明） */
  const radarLabels = []
  /*
   * 雷达名牌随相机高度自适应：贴地时名牌放低（引线短、几乎站在雷达头上），
   * 拉远时抬高并放大字号——和测点、分区标签同一套口径，三层的"随缩放自适应"表现一致。
   * 节流到约 8 fps：名字只有几台，但也没必要每帧重算。
   */
  let radarLabelTick = 0
  const radarLabelHandler = () => {
    if (!radarLabels.length) return
    const now = Date.now()
    if (now - radarLabelTick < 120) return
    radarLabelTick = now
    const carto = Cesium.Cartographic.fromCartesian(viewer.camera.positionWC)
    const camHeight = carto ? Math.max(0, carto.height) : 0
    const t = Math.min(1, Math.max(0, (camHeight - 150) / (2400 - 150)))
    const lift = 6 + 26 * t          // 名牌相对天线头再抬多少米
    const scale = 0.9 + 0.45 * t     // 字号倍数
    for (const handle of radarLabels) {
      const tip = localToWorld(handle.frame, [0, 0, handle.headHeight + lift])
      handle.label.position = tip
      handle.label.label.scale = scale
      handle.leader.polyline.positions = [handle.ground, tip]
    }
  }
  viewer.scene.postRender.addEventListener(radarLabelHandler)
  // 两个图层开关与当前选中：每台雷达四片形状的可见性由它们合成（见 applyRadarVisibility）
  let sectorVisible = true
  let verticalVisible = false
  // 地形裁剪覆盖层默认显示：它是"真的看得到哪儿"的答案，比理论扇面更该先看到
  let coverageVisible = true
  let activeRadarKey = null
  // 下标用于配色：RADAR_COLORS 按声明顺序分配，保证「同一台雷达每次打开都是同一个颜色」
  ;(config.radars || []).forEach((radar, index) => {
    const group = addRadar(viewer, radar, index, token)
    decorations.push(...group.entities)
    if (group.labelHandle) radarLabels.push(group.labelHandle)
    radarGroups.set(radar.deviceId ?? radar.code, group)
  })
  radarLabelHandler()

  // 局部场景保持固定可读光照，避免系统时间改变导致夜间全黑。
  viewer.scene.light = new Cesium.DirectionalLight({
    direction: Cesium.Cartesian3.normalize(
      new Cesium.Cartesian3(0.35, 0.25, -1),
      new Cesium.Cartesian3(),
    ),
    intensity: 2.2,
  })

  async function flyHome({ duration = 2.0 } = {}) {
    const camera = cameraOf(config)
    if (config.coordinateMode === 'ENU') {
      const anchor = Cesium.Cartesian3.fromDegrees(
        finite(config.anchorLongitude),
        finite(config.anchorLatitude),
        finite(config.anchorHeight),
      )
      // 第五版：**唯一一个把相机放到"刚好装下"的地方**（applyFitView）。
      // 默认视角、全局视角按钮、缩放上限三者共用同一个距离，语义只有一份：
      //   距离 = fitRangeFor(模型包围球半径, 画布)  ← 余量由 VITE_CAMERA_FIT_MARGIN 控制
      //   VITE_CAMERA_FIT_MARGIN=1.0 → 完全贴边；VITE_MAX_ZOOM_FACTOR=1.0 → 不允许比它更远
      applyFitView(viewer, anchor, camera.heading, camera.pitch, duration)
      return
    }
    await viewer.flyTo(asset, { duration })
  }

  function setActiveRadar(deviceId) {
    activeRadarKey = deviceId == null ? null : String(deviceId)
    for (const [id, group] of radarGroups.entries()) {
      const active = String(id) === activeRadarKey
      // 视线只显示选中那台的（场景大时上千条线会把画面糊住），水平扇面则两台都留
      for (const entity of group.targetEntities) entity.show = active

      // 水平扇面：选中的提亮、其余压暗。同色面叠在一起时，「谁在看哪片」只能靠这个区分。
      const sector = group.sector
      if (sector) {
        const c = Cesium.Color.fromCssColorString(group.faceColor)
        // 理论视场始终是"提示层"：选中时也只是略微提亮（0.16），不能看着像已核验的覆盖。
        sector.face.polygon.material = c.withAlpha(active ? 0.16 : 0.05)
        sector.outline.polyline.width = active ? 2.2 : 1.0
        sector.outline.polyline.material = new Cesium.PolylineDashMaterialProperty({
          color: c.withAlpha(active ? 0.9 : 0.4),
          dashLength: 14,
        })
        sector.upper.polyline.width = active ? 1.6 : 0.8
        sector.lower.polyline.width = active ? 1.6 : 0.8
      }
      applyRadarVisibility(group, active)
    }
  }

  /**
   * 理论视场图层的整体显隐（大屏上的「雷达理论视场」开关）。
   *
   * <p>它与「垂直视场边界」两个开关各自独立，所以可见性是**两处的合取**，
   * 统一在这里算——分别写在两个函数里迟早会出现"开了图层但没刷新"的漂移。</p>
   */
  function setSectorVisible(show) {
    sectorVisible = !!show
    for (const [id, group] of radarGroups.entries()) {
      applyRadarVisibility(group, String(id) === activeRadarKey)
    }
  }

  /**
   * 垂直视场上下边界（±verticalHalfAngle）的显隐，默认关闭。
   *
   * <p>为什么默认关：它和水平扇面叠在一起时会被读成"另一块覆盖区"（2026-09-20 用户反馈
   * 「为什么有三个区域」）。需要看垂直范围时再打开；打开后也**只画选中那一台**，
   * 否则两台雷达的四片轮廓同时铺开，又回到同一个误会。</p>
   */
  function setCoverageVisible(show) {
    coverageVisible = !!show
    for (const [id, group] of radarGroups) {
      applyRadarVisibility(group, String(id) === activeRadarKey)
    }
  }

  function setVerticalVisible(show) {
    verticalVisible = !!show
    for (const [id, group] of radarGroups.entries()) {
      applyRadarVisibility(group, String(id) === activeRadarKey)
    }
  }

  /** 两处开关 + 是否选中，合成每台雷达四片形状的可见性。 */
  function applyRadarVisibility(group, active) {
    const sector = group.sector
    const coverage = group.coverage
    if (!sector) return
    // 理论扇面面片与裁剪覆盖同时开着会糊成一片：有裁剪层时，面片只留虚线轮廓，
    // 实心区域交给裁剪层（它才是"看得到哪儿"）。这条规则写在 applyRadarVisibility 里，
    // 因为它是"三层的可见性怎么合成"的一部分，散到别处就会出现"开了图层没刷新"。
    const hasCoverage = !!(coverage && coverage.face)
    sector.face.show = sectorVisible && !(hasCoverage && coverageVisible)
    sector.outline.show = sectorVisible
    sector.upper.show = sectorVisible && verticalVisible && active
    sector.lower.show = sectorVisible && verticalVisible && active
    if (coverage) {
      if (coverage.face) coverage.face.show = coverageVisible
      if (coverage.outline) coverage.outline.show = coverageVisible
    }
  }

  function pickRadarId(picked) {
    const properties = picked?.id?.properties
    const kind = properties?.kind?.getValue?.() ?? properties?.kind
    if (kind !== 'radar') return null
    return properties?.deviceId?.getValue?.() ?? properties?.deviceId ?? null
  }

  const firstRadar = (config.radars || [])[0]
  if (firstRadar) setActiveRadar(firstRadar.deviceId ?? firstRadar.code)

  /*
   * 进场取景放在**建场景函数的最后一步**（2026-09-21 第七版）。
   *
   * 为什么放这里：调用方（ScreenView）在建完场景之后还有一串操作（图层开关、热力层同步…），
   * 其中任何一个抛异常，都会让它后面的"进场取景"整段被跳过——用户看到的就是
   * "模型出来了、但相机停在默认全球视角，进来看是个小点"。
   * 放在本函数内部，只要场景建成就必然执行；再补一发延迟调用兜住"模型解析晚一拍"。
   */
  try {
    fitSceneView(viewer)
  } catch (error) {
    console.warn('[cesium] 进场取景失败（不影响场景显示）：', error)
  }
  setTimeout(() => {
    try {
      if (!viewer.isDestroyed?.()) fitSceneView(viewer)
    } catch {
      /* 忽略 */
    }
  }, 1200)

  return {
    config,
    asset,
    flyHome,
    setActiveRadar,
    setSectorVisible,
    setVerticalVisible,
    setCoverageVisible,
    pickRadarId,
    destroy() {
      viewer.scene.postRender.removeEventListener(radarLabelHandler)
      for (const entity of decorations) viewer.entities.remove(entity)
      if (!asset.isDestroyed?.()) viewer.scene.primitives.remove(asset)
    },
  }
}
