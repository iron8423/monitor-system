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
function addRadar(viewer, radar) {
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
  add('sector', {
    polygon: {
      hierarchy: new Cesium.PolygonHierarchy(fan),
      perPositionHeight: true,
      material: Cesium.Color.fromCssColorString(statusColor).withAlpha(0.10),
      outline: false,
    },
    properties: { kind: 'radar-decoration', deviceId: radar.deviceId },
  })
  add('sector-outline', {
    polyline: {
      positions: [start, ...arc, start],
      width: 1.4,
      material: Cesium.Color.fromCssColorString(statusColor).withAlpha(0.65),
    },
    properties: { kind: 'radar-decoration', deviceId: radar.deviceId },
  })
  // 上下边界共同表达垂直视场；中心扇面保留为方向提示，但不再冒充完整覆盖体。
  add('frustum-upper-outline', {
    polyline: {
      positions: [start, ...upperArc, start],
      width: 1.0,
      material: Cesium.Color.fromCssColorString(statusColor).withAlpha(0.34),
    },
    properties: { kind: 'radar-decoration', deviceId: radar.deviceId },
  })
  add('frustum-lower-outline', {
    polyline: {
      positions: [start, ...lowerArc, start],
      width: 1.0,
      material: Cesium.Color.fromCssColorString(statusColor).withAlpha(0.34),
    },
    properties: { kind: 'radar-decoration', deviceId: radar.deviceId },
  })

  for (const target of radar.targets || []) {
    const lon = Number(target.longitude)
    const lat = Number(target.latitude)
    if (!Number.isFinite(lon) || !Number.isFinite(lat)) continue
    const altitude = finite(target.altitude) + Math.max(0, finite(target.reflectorHeightM, 0))
    const end = Cesium.Cartesian3.fromDegrees(lon, lat, altitude)
    const active = String(target.calibrationStatus || '').toUpperCase() === 'ACTIVE'
    const visible = target.lineOfSight === true
    const color = active && visible ? '#55e6a5' : visible ? '#f4c95d' : '#ff6b6b'
    const line = add(`target-${target.bindingId ?? target.pointId}`, {
      show: false,
      polyline: {
        positions: [start, end],
        width: active && visible ? 2.2 : 1.6,
        material: new Cesium.PolylineDashMaterialProperty({
          color: Cesium.Color.fromCssColorString(color).withAlpha(0.82),
          dashLength: active && visible ? 18 : 10,
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
  return { entities, targetEntities }
}

function cameraOf(config) {
  return {
    heading: finite(config.cameraHeadingDegrees, DEFAULT_CAMERA.heading),
    pitch: finite(config.cameraPitchDegrees, DEFAULT_CAMERA.pitch),
    range: Math.max(1, finite(config.cameraRange, DEFAULT_CAMERA.range)),
  }
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
  for (const radar of config.radars || []) {
    const group = addRadar(viewer, radar)
    decorations.push(...group.entities)
    radarGroups.set(radar.deviceId ?? radar.code, group)
  }

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
      viewer.camera.flyToBoundingSphere(
        new Cesium.BoundingSphere(anchor, Math.max(30, camera.range / 2.4)),
        {
          offset: new Cesium.HeadingPitchRange(
            Cesium.Math.toRadians(camera.heading),
            Cesium.Math.toRadians(camera.pitch),
            camera.range,
          ),
          duration,
        },
      )
      return
    }
    await viewer.flyTo(asset, { duration })
  }

  function setActiveRadar(deviceId) {
    for (const [id, group] of radarGroups.entries()) {
      const show = String(id) === String(deviceId)
      for (const entity of group.targetEntities) entity.show = show
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
    pickRadarId,
    destroy() {
      for (const entity of decorations) viewer.entities.remove(entity)
      if (!asset.isDestroyed?.()) viewer.scene.primitives.remove(asset)
    },
  }
}
