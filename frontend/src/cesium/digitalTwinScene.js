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

function addRadar(viewer, radar, index = 0) {
  const frame = radarFrame(radar)
  if (!frame) return { entities: [], targetEntities: [] }
  const id = radar.deviceId ?? radar.code
  const entities = []
  const targetEntities = []
  const add = (suffix, options) => {
    const entity = viewer.entities.add({ id: `dt-radar-${id}-${suffix}`, ...options })
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
    label: {
      text: radar.name || radar.code || '雷达',
      font: '13px "Microsoft YaHei", sans-serif',
      fillColor: Cesium.Color.WHITE,
      showBackground: true,
      backgroundColor: Cesium.Color.fromCssColorString('#06101f').withAlpha(0.82),
      backgroundPadding: new Cesium.Cartesian2(8, 4),
      pixelOffset: new Cesium.Cartesian2(0, -26),
      verticalOrigin: Cesium.VerticalOrigin.BOTTOM,
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
    sector: { face: sectorFace, outline: sectorOutline, upper: sectorUpper, lower: sectorLower },
    coverage: { face: coverageFace, outline: coverageOutline },
  }
}

function cameraOf(config) {
  // 取景微调（2026-09-21）：数据库里存的相机参数（range 1650）让 1000×750m 的资产
  // 只占屏幕中间一小块，四周全是空白——观感上是"一张纸浮在黑底上"。
  // 这里用环境变量按比例收紧，不动数据库、也不改其它场景的存档参数：
  // VITE_CAMERA_RANGE_SCALE=0.7 → 1650m 变约 1155m；VITE_CAMERA_PITCH 可覆盖俯角。
  const rangeScale = parseFloat(import.meta.env.VITE_CAMERA_RANGE_SCALE) || 1
  const pitchOverride = parseFloat(import.meta.env.VITE_CAMERA_PITCH)
  const camera = {
    heading: finite(config.cameraHeadingDegrees, DEFAULT_CAMERA.heading),
    pitch: Number.isFinite(pitchOverride)
      ? pitchOverride
      : finite(config.cameraPitchDegrees, DEFAULT_CAMERA.pitch),
    range: Math.max(1, finite(config.cameraRange, DEFAULT_CAMERA.range)),
  }
  camera.range *= rangeScale
  return camera
}

/**
 * 创建项目数字孪生场景。调用方负责处理项目切换竞态，并在不再使用时 destroy()。
 */
export async function createDigitalTwinScene(viewer, rawConfig) {
  const config = validateConfig(rawConfig)
  const modelMatrix = modelMatrixOf(config)
  const asset = await loadAsset(viewer, config, modelMatrix)
  const decorations = []
  const radarGroups = new Map()
  // 两个图层开关与当前选中：每台雷达四片形状的可见性由它们合成（见 applyRadarVisibility）
  let sectorVisible = true
  let verticalVisible = false
  // 地形裁剪覆盖层默认显示：它是"真的看得到哪儿"的答案，比理论扇面更该先看到
  let coverageVisible = true
  let activeRadarKey = null
  // 下标用于配色：RADAR_COLORS 按声明顺序分配，保证「同一台雷达每次打开都是同一个颜色」
  ;(config.radars || []).forEach((radar, index) => {
    const group = addRadar(viewer, radar, index)
    decorations.push(...group.entities)
    radarGroups.set(radar.deviceId ?? radar.code, group)
  })

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
      // 取景自适应（2026-09-21）：原来用的是配置里写死的 range，模型大小/窗口宽高比一变
      // 就会"要么塞不满、要么跑出画面"。这里按**模型包围球**和当前画布 FOV 反算距离，
      // 保证任何窗口比例下模型都刚好填满（留 12% 余量）。VITE_CAMERA_FIT=0 可退回固定值。
      const fit = String(import.meta.env.VITE_CAMERA_FIT ?? '1') !== '0'
      // 取景半径优先用**场景平面尺寸**（宽/深），包围球会把 200m 的山体高度也算进去，
      // 结果相机被推到模型外很远——上一版"点了全局视角反而什么都看不到"就是这么来的。
      const dim = Array.isArray(config.dimensionsMetres) ? config.dimensionsMetres : []
      const span = Math.max(Number(dim[0]) || 0, Number(dim[1]) || 0)
      const radius = Math.max(span / 2, Number(asset?.boundingSphere?.radius) || 0, 30)
      let range = camera.range
      if (fit) {
        const canvas = viewer.canvas
        const aspect = canvas.clientWidth / Math.max(1, canvas.clientHeight)
        const fovy = viewer.camera.frustum.fovy || Cesium.Math.toRadians(60)
        const hfov = 2 * Math.atan(Math.tan(fovy / 2) * aspect)
        const half = Math.min(fovy, hfov) / 2
        range = (radius / Math.sin(half)) * 1.12
      }
      viewer.camera.flyToBoundingSphere(
        new Cesium.BoundingSphere(anchor, radius),
        {
          offset: new Cesium.HeadingPitchRange(
            Cesium.Math.toRadians(camera.heading),
            Cesium.Math.toRadians(camera.pitch),
            range,
          ),
          duration,
        },
      )
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
      for (const entity of decorations) viewer.entities.remove(entity)
      if (!asset.isDestroyed?.()) viewer.scene.primitives.remove(asset)
    },
  }
}
