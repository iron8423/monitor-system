import * as Cesium from 'cesium'

const CONFIG_URL = '/models/mountain-demo/scene-config.json'

function localToWorld(frame, point) {
  return Cesium.Matrix4.multiplyByPoint(
    frame,
    new Cesium.Cartesian3(point[0], point[1], point[2]),
    new Cesium.Cartesian3(),
  )
}

function addRadar(viewer, frame, radar) {
  const [x, y, ground] = radar.localPosition
  const radarId = radar.id ?? radar.code ?? 'radar'
  const heading = Cesium.Math.toRadians(radar.headingDegrees)
  const entities = []

  const add = (options) => {
    const entity = viewer.entities.add(options)
    entities.push(entity)
    return entity
  }

  add({
    id: `mountain-radar-${radarId}-platform`,
    position: localToWorld(frame, [x, y, ground + 0.8]),
    cylinder: {
      length: 1.6,
      topRadius: 7.5,
      bottomRadius: 8.5,
      material: Cesium.Color.fromCssColorString('#526171'),
      outline: true,
      outlineColor: Cesium.Color.fromCssColorString('#91a6b8'),
    },
    properties: { kind: 'mountain-decoration' },
  })

  add({
    id: `mountain-radar-${radarId}-mast`,
    position: localToWorld(frame, [x, y, ground + 5.7]),
    cylinder: {
      length: 8.2,
      topRadius: 0.55,
      bottomRadius: 0.85,
      material: Cesium.Color.fromCssColorString('#b5c0cb'),
    },
    properties: { kind: 'mountain-decoration' },
  })

  const head = localToWorld(frame, [x, y, ground + 10.7])
  add({
    id: `mountain-radar-${radarId}-head`,
    position: head,
    ellipsoid: {
      radii: new Cesium.Cartesian3(2.6, 2.6, 1.8),
      material: Cesium.Color.fromCssColorString('#d7e2ea'),
      outline: true,
      outlineColor: Cesium.Color.fromCssColorString('#35c9ff'),
    },
    label: {
      text: radar.name || radar.code || '毫米波雷达（模拟）',
      font: '13px "Microsoft YaHei", sans-serif',
      fillColor: Cesium.Color.WHITE,
      showBackground: true,
      backgroundColor: Cesium.Color.fromCssColorString('#06101f').withAlpha(0.82),
      backgroundPadding: new Cesium.Cartesian2(8, 4),
      pixelOffset: new Cesium.Cartesian2(0, -30),
      verticalOrigin: Cesium.VerticalOrigin.BOTTOM,
      disableDepthTestDistance: Number.POSITIVE_INFINITY,
    },
    properties: { kind: 'mountain-decoration' },
  })

  // 扫描扇区是一张从雷达头部向山体张开的半透明面；它只表达覆盖方向与范围，
  // 不冒充真实波束强度。末端略抬高，让扇面穿过主坡面，构图上能看到覆盖关系。
  const start = [x, y, ground + 10.7]
  const fan = [localToWorld(frame, start)]
  const arc = []
  const samples = 18
  for (let i = 0; i <= samples; i += 1) {
    const angle = heading - Cesium.Math.toRadians(radar.halfAngleDegrees)
      + Cesium.Math.toRadians(radar.halfAngleDegrees * 2) * (i / samples)
    const distance = radar.range
    const end = [
      x + Math.sin(angle) * distance,
      y + Math.cos(angle) * distance,
      ground + 42,
    ]
    const world = localToWorld(frame, end)
    fan.push(world)
    arc.push(world)
  }

  add({
    id: `mountain-radar-${radarId}-sector`,
    polygon: {
      hierarchy: new Cesium.PolygonHierarchy(fan),
      perPositionHeight: true,
      material: Cesium.Color.fromCssColorString('#20c8ff').withAlpha(0.13),
      outline: false,
    },
    properties: { kind: 'mountain-decoration' },
  })
  add({
    id: `mountain-radar-${radarId}-sector-outline`,
    polyline: {
      positions: [fan[0], ...arc, fan[0]],
      width: 1.5,
      material: Cesium.Color.fromCssColorString('#56dcff').withAlpha(0.7),
    },
    properties: { kind: 'mountain-decoration' },
  })

  return entities
}

/** 加载本地 GLB，并把局部 +X/+Y/+Z 对齐到地球坐标的东/北/上。 */
export async function createMountainScene(viewer) {
  const response = await fetch(CONFIG_URL)
  if (!response.ok) throw new Error(`山地配置加载失败（HTTP ${response.status}）`)
  const config = await response.json()
  const { longitude, latitude, height } = config.anchor
  const anchor = Cesium.Cartesian3.fromDegrees(longitude, latitude, height)
  const frame = Cesium.Transforms.eastNorthUpToFixedFrame(anchor)

  const model = await Cesium.Model.fromGltfAsync({
    url: config.asset,
    modelMatrix: frame,
    upAxis: Cesium.Axis.Z,
    forwardAxis: Cesium.Axis.X,
    allowPicking: false,
    shadows: Cesium.ShadowMode.ENABLED,
  })
  viewer.scene.primitives.add(model)
  const radars = config.radars || (config.radar ? [config.radar] : [])
  const decorations = radars.flatMap((radar) => addRadar(viewer, frame, radar))

  // 局部模型视角下关掉太阳造成的昼夜变化，让离线演示在任何系统时间都可读。
  viewer.scene.light = new Cesium.DirectionalLight({
    direction: Cesium.Cartesian3.normalize(new Cesium.Cartesian3(0.35, 0.25, -1), new Cesium.Cartesian3()),
    intensity: 2.2,
  })

  function flyHome({ duration = 2.0 } = {}) {
    viewer.camera.flyToBoundingSphere(new Cesium.BoundingSphere(anchor, 180), {
      offset: new Cesium.HeadingPitchRange(
        Cesium.Math.toRadians(config.camera.headingDegrees),
        Cesium.Math.toRadians(config.camera.pitchDegrees),
        config.camera.range,
      ),
      duration,
    })
  }

  return {
    config,
    flyHome,
    destroy() {
      for (const entity of decorations) viewer.entities.remove(entity)
      if (!model.isDestroyed()) viewer.scene.primitives.remove(model)
    },
  }
}
