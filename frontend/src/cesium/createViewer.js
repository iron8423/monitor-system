import * as Cesium from 'cesium'
import 'cesium/Build/Cesium/Widgets/widgets.css'

const TOKEN = import.meta.env.VITE_CESIUM_ION_TOKEN || ''
const TERRAIN_MODE = import.meta.env.VITE_TERRAIN_MODE || 'ion'

/**
 * 是否配了 Cesium ion token —— 给界面用（大屏顶栏那条「未配 token」的提示）。
 *
 * 为什么值得单独暴露：没有 token 时大屏**不白屏**（走椭球 + 兜底底图），
 * 于是「没有地形、没有卫星影像」这件事在界面上只剩两个灰字，第一次跑这个项目的人
 * （比如换台机器 clone 下来）根本不知道自己少配了什么——而 `.env.local` 是 gitignore 的，
 * 每个人都要自己配一次。把这件事显式说出来，比让人去猜要省事得多。
 */
export const ION_CONFIGURED = Boolean(TOKEN)
export const ION_TERRAIN_MODE = TERRAIN_MODE

/** 离线兜底用的深色底图（实测这台机器可达；Cesium 官方影像走 ion，需要 token） */
const FALLBACK_IMAGERY = 'https://basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png'

/**
 * 创建 Cesium 视图。
 *
 * 设计原则：**先保证出画面，再逐步升级**。
 * 所以这里同步地、用最保守的配置把 viewer 建起来（无地形、深色球体），
 * 地形和影像在之后异步加载，加载失败就降级，绝不白屏。
 */
export function createViewer(container) {
  if (TOKEN) {
    Cesium.Ion.defaultAccessToken = TOKEN
  }

  const viewer = new Cesium.Viewer(container, {
    baseLayer: false, // 影像层我们自己加，便于失败降级
    terrainProvider: new Cesium.EllipsoidTerrainProvider(),
    animation: false,
    timeline: false,
    baseLayerPicker: false,
    geocoder: false,
    homeButton: false,
    sceneModePicker: false,
    navigationHelpButton: false,
    fullscreenButton: false,
    infoBox: false,
    selectionIndicator: false,
    shouldAnimate: true,
  })

  const scene = viewer.scene
  scene.globe.baseColor = Cesium.Color.fromCssColorString('#0b1622')
  scene.globe.enableLighting = false
  scene.globe.depthTestAgainstTerrain = true
  scene.fog.enabled = true
  scene.fog.density = 0.0002
  scene.screenSpaceCameraController.minimumZoomDistance = 30
  scene.screenSpaceCameraController.maximumZoomDistance = 60000
  // 版权署名容器保留（Cesium 与影像提供方要求），只在样式上收敛
  viewer.cesiumWidget.creditContainer.style.opacity = '0.55'

  return viewer
}

/**
 * 异步加载真实地形。失败不抛错，返回状态给界面显示。
 * @returns {Promise<'ok'|'skipped'|'failed'>}
 */
export async function setupTerrain(viewer) {
  if (TERRAIN_MODE !== 'ion' || !TOKEN) {
    return 'skipped'
  }
  try {
    const provider = Cesium.createWorldTerrainAsync
      ? await Cesium.createWorldTerrainAsync({ requestVertexNormals: true, requestWaterMask: false })
      : await Cesium.CesiumTerrainProvider.fromIonAssetId(1, { requestVertexNormals: true })
    viewer.terrainProvider = provider
    viewer.scene.globe.depthTestAgainstTerrain = true
    return 'ok'
  } catch (error) {
    console.warn('[cesium] 真实地形加载失败，已降级为椭球：', error?.message || error)
    return 'failed'
  }
}

/**
 * 异步加载影像底图：优先 Cesium ion（卫星影像），失败退回深色底图。
 * @returns {Promise<'ion'|'fallback'|'failed'>}
 */
export async function setupImagery(viewer) {
  if (TOKEN) {
    try {
      const provider = await Cesium.IonImageryProvider.fromAssetId(2)
      viewer.imageryLayers.addImageryProvider(provider)
      return 'ion'
    } catch (error) {
      console.warn('[cesium] ion 影像加载失败，改用兜底底图：', error?.message || error)
    }
  }
  try {
    viewer.imageryLayers.addImageryProvider(
      new Cesium.UrlTemplateImageryProvider({
        url: FALLBACK_IMAGERY,
        credit: '© OpenStreetMap contributors © CARTO',
        maximumLevel: 18,
      }),
    )
    return 'fallback'
  } catch (error) {
    console.warn('[cesium] 兜底底图也不可用：', error?.message || error)
    return 'failed'
  }
}

const HEADING = Cesium.Math.toRadians(10)
const PITCH = Cesium.Math.toRadians(-42)

/**
 * 相机飞到一组测点。
 *
 * 坑记在这里：不能用 `flyTo({ destination: Rectangle, orientation: {pitch} })` ——
 * 俯仰角会盖掉「框住目标」的构图结果，实测相机会停在目标前方约 10km，
 * 目标被推到屏幕外面（屏幕 y 坐标 3000+）。
 * `flyToBoundingSphere` + HeadingPitchRange 才是「目标居中 + 有俯仰角」的正确姿势。
 */
export function flyToPoints(viewer, points, { duration = 2.2 } = {}) {
  const sphere = boundingSphereOf(points)
  if (!sphere) return
  viewer.camera.flyToBoundingSphere(sphere, {
    offset: new Cesium.HeadingPitchRange(HEADING, PITCH, Math.max(sphere.radius * 3.2, 1500)),
    duration,
  })
}

/** 相机飞到单个测点（侧栏点击、告警定位用） */
export function flyToPoint(viewer, point, { duration = 1.6 } = {}) {
  const lon = Number(point?.longitude)
  const lat = Number(point?.latitude)
  if (!Number.isFinite(lon) || !Number.isFinite(lat)) return
  const center = Cesium.Cartesian3.fromDegrees(lon, lat, (Number(point.altitude) || 0) + 40)
  const sphere = new Cesium.BoundingSphere(center, 120)
  viewer.camera.flyToBoundingSphere(sphere, {
    offset: new Cesium.HeadingPitchRange(HEADING, Cesium.Math.toRadians(-35), 620),
    duration,
  })
}

/** 由测点集合算出包围球（半径给个下限，免得 2 个点靠太近时贴脸） */
function boundingSphereOf(points) {
  const positions = points
    .filter((p) => Number.isFinite(Number(p.longitude)) && Number.isFinite(Number(p.latitude)))
    .map((p) =>
      Cesium.Cartesian3.fromDegrees(
        Number(p.longitude),
        Number(p.latitude),
        (Number(p.altitude) || 0) + 40,
      ),
    )
  if (!positions.length) return null
  const sphere = Cesium.BoundingSphere.fromPoints(positions)
  sphere.radius = Math.max(sphere.radius, 600)
  return sphere
}

export { Cesium }
