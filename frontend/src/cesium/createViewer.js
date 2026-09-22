import * as Cesium from 'cesium'
import 'cesium/Build/Cesium/Widgets/widgets.css'

import { applyRenderProfile } from '@/cesium/renderProfile'

const TOKEN = import.meta.env.VITE_CESIUM_ION_TOKEN || ''
const TERRAIN_MODE = import.meta.env.VITE_TERRAIN_MODE || 'ion'
const SCENE_MODE = import.meta.env.VITE_SCENE_MODE || 'mountain'

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
export const LOCAL_SCENE_ENABLED = SCENE_MODE === 'mountain'
/*
 * 远景层（2026-09-22 用户反馈"转一下就露出地块边缘的虚空"）。
 *
 * 根因是几何事实：本地方形地块是有限的，只要视线接近水平，地块边界必然进画面，
 * 边界之外什么都没有 → 看起来像"一块悬浮的板子"。此前试过扩大地块（3km→6km），
 * 但按 -30° 俯角算需要 20km 见方才推得出画面，而且纹理会被摊薄到没意义。
 *
 * 正确做法是让"地块之外"有东西：给椭球铺一层真实全球影像（ion 的 Bing 影像，
 * WGS-84，与我们的地块不错位），于是任意角度、任意缩放看到的都是真实地面，
 * 而不是背景色。`VITE_FARFIELD=off` 可退回"深色底 + 只有地块"的老行为。
 */
const FARFIELD = String(import.meta.env.VITE_FARFIELD || 'ion').toLowerCase()
/** 远景要不要用 Cesium 全球地形（ion asset 1）：有真实起伏，但需要联网且更吃性能。 */
const FARFIELD_TERRAIN = String(import.meta.env.VITE_FARFIELD_TERRAIN || '0') === '1'

/**
 * 场景底色（椭球底色）按主题给两个值。
 *
 * 为什么 3D 也要跟着主题走：白天模式下工作台是浅色的，如果大屏的"天空"仍是深夜蓝黑，
 * 切过来会像两个系统。底线是不动影像与地形本身——那是数据，不是装饰。
 */
// 背景/地面底色（2026-09-21 用户反馈"左上角仍有虚空"）：从接近纯黑改成偏蓝的深石板色，
// 配一层大气，观感更"有天空"、也不刺眼；浅色主题保持原来的浅灰蓝。
// 背景/地面底色（2026-09-21 第二轮）：再提亮一档并偏蓝，让"模型之外"看起来是
// 天空与雾，而不是黑洞——旋转到地平线方向时尤其明显。
const GLOBE_BASE_COLOR = { dark: '#1b3550', light: '#cfdbe8' }

/*
 * 雾（2026-09-22）：Cesium 的雾是"距离越远越白"的航空透视，公式是
 *   fog = 1 - exp(-((k·s + k) · s · (1 + k)))，  s = 距离 × density
 * 其中 k = fog.visualDensityScalar。**之前一直看不见雾的原因就在这里**：
 * 只调 density，而 k 很小（默认 0.001 量级），在 3~10km 的尺度上几乎不起作用；
 * 而且 applyViewerTheme() 每次切主题都会把 density 重置成固定值，把环境变量覆盖掉。
 * 现在两个参数都走环境变量，切主题也不再回写。
 */
const FOG_DENSITY = parseFloat(import.meta.env.VITE_FOG_DENSITY)
const FOG_VISUAL_SCALAR = parseFloat(import.meta.env.VITE_FOG_VISUAL_SCALAR)

/** 把当前主题应用到 viewer（创建时调一次；主题切换时再调） */
export function applyViewerTheme(viewer, theme = document.documentElement.dataset.theme) {
  if (!viewer) return
  const key = theme === 'light' ? 'light' : 'dark'
  viewer.scene.globe.baseColor = Cesium.Color.fromCssColorString(GLOBE_BASE_COLOR[key])
  // 雾密度也分两档：浅色底配原来的雾会显得"发灰"。
  // 环境变量优先——否则切一次主题就会把调好的雾"打回原形"（这是个真发生过的问题）。
  if (!Number.isFinite(FOG_DENSITY)) {
    viewer.scene.fog.density = key === 'light' ? 0.00012 : 0.0002
  }
}

/** 全球模式的远程兜底底图；默认 mountain 模式不会请求它。 */
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
  // 画布分辨率（2026-09-21 用户反馈"点和标签发虚"）：
  // Cesium 默认 useBrowserRecommendedResolution=true，按 CSS 分辨率渲染——
  // 在 2x 屏上等于把整幅三维画面放大一倍，点/编号/视场线全被插值糊掉。
  // 改成按设备像素渲染（上限 2，默认 1.5 兼顾性能）。
  const scale = Math.min(
    Math.max(parseFloat(import.meta.env.VITE_RESOLUTION_SCALE) || 1.5, 1),
    Math.min(window.devicePixelRatio || 1, 2),
  )
  viewer.useBrowserRecommendedResolution = false
  viewer.resolutionScale = scale

  applyViewerTheme(viewer)
  scene.globe.enableLighting = false
  scene.globe.depthTestAgainstTerrain = true
  // 舒适的天空/大气：关掉默认星空盒、开大气散射，地平线自然过渡（不再是一块纯黑背景）
  scene.skyBox.show = false
  scene.skyAtmosphere.show = true
  scene.backgroundColor = Cesium.Color.fromCssColorString(GLOBE_BASE_COLOR.dark)
  scene.fog.enabled = true
  // 雾：密度 + 视觉密度标量都要给（见文件开头的公式说明），否则等于没开。
  scene.fog.density = Number.isFinite(FOG_DENSITY) ? FOG_DENSITY : 0.0002
  if (Number.isFinite(FOG_VISUAL_SCALAR)) {
    scene.fog.visualDensityScalar = FOG_VISUAL_SCALAR
  }
  scene.screenSpaceCameraController.minimumZoomDistance = 60
  // 最大缩放距离（2026-09-21 用户反馈"回到全局视角就看不到东西"）：
  // 原来给到 60km，等于允许用户把 1km 的场址缩成一个看不见的点。
  // 收到 4000m：还能拉远看全貌，但不会拉到虚空里。可用 VITE_MAX_ZOOM_DISTANCE 覆盖。
  scene.screenSpaceCameraController.maximumZoomDistance =
    parseFloat(import.meta.env.VITE_MAX_ZOOM_DISTANCE) || 12000
  // 鼠标操作映射（2026-09-21 反馈"有时旋转有时平移"）：
  // Cesium 默认左键在"有地球的地方"是转地球、在虚空里是转相机，还会因为地形/椭球
  // 命中与否表现不一致。这里把三种操作钉死，行为可预期：
  //   左键拖 = 绕场景旋转；右键拖 = 平移；滚轮 = 缩放
  const cam = scene.screenSpaceCameraController
  cam.rotateEventTypes = Cesium.CameraEventType.LEFT_DRAG
  cam.translateEventTypes = [
    Cesium.CameraEventType.RIGHT_DRAG,
    Cesium.CameraEventType.MIDDLE_DRAG,
  ]
  cam.zoomEventTypes = [Cesium.CameraEventType.WHEEL, Cesium.CameraEventType.PINCH]
  cam.tiltEventTypes = [
    Cesium.CameraEventType.MIDDLE_DRAG,
    Cesium.CameraEventType.PINCH,
    { eventType: Cesium.CameraEventType.LEFT_DRAG, modifier: Cesium.KeyboardEventModifier.CTRL },
  ]
  cam.enableLook = false
  // 版权署名容器保留（Cesium 与影像提供方要求），只在样式上收敛
  viewer.cesiumWidget.creditContainer.style.opacity = '0.55'

  // 渲染档位（默认 flat = 历史行为；VITE_RENDER_MODE=lit 时切成受光渲染）
  applyRenderProfile(viewer)

  return viewer
}

/**
 * 异步加载真实地形。失败不抛错，返回状态给界面显示。
 * @returns {Promise<'ok'|'skipped'|'failed'>}
 */
export async function setupTerrain(viewer) {
  if (LOCAL_SCENE_ENABLED && !FARFIELD_TERRAIN) {
    return 'skipped'
  }
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
 * @returns {Promise<'ion'|'offline'|'fallback'|'failed'>}
 */
export async function setupImagery(viewer) {
  // 本地地形场景默认也铺一层远景影像：地块之外要有真实地面，否则一转就露边。
  // VITE_FARFIELD=off → 退回"深色椭球 + 只有地块"的老行为（断网兜底）。
  if (LOCAL_SCENE_ENABLED && FARFIELD === 'off') {
    return 'offline'
  }
  /*
   * 本地远景层（2026-09-22 第二轮）：`tools/imagery_fetch/fetch_farfield.py` 抓下来的
   * Google 瓦片金字塔放在 `public/farfield/`，由 manifest.json 描述范围与层级。
   * 好处：和地块纹理**同源同时期**（色调/地物都对得上），且运行期不联网。
   * FARFIELD=local → 只用本地；local+ion → 本地之上再垫一层 ion 影像兜更远的地方。
   */
  if (FARFIELD === 'local' || FARFIELD === 'local+ion') {
    const localStatus = await addLocalFarfield(viewer)
    if (localStatus) {
      if (FARFIELD === 'local+ion' && TOKEN) {
        try {
          const provider = await Cesium.IonImageryProvider.fromAssetId(2)
          const layer = viewer.imageryLayers.addImageryProvider(provider)
          viewer.imageryLayers.lowerToBottom(layer)
          applyFarfieldTone(layer, true)
        } catch (error) {
          console.warn('[cesium] 远景兜底影像不可用（不影响本地远景）：', error?.message || error)
        }
      }
      return localStatus
    }
    console.warn('[cesium] 没有找到本地远景层，退回在线影像')
  }
  if (TOKEN) {
    try {
      const provider = await Cesium.IonImageryProvider.fromAssetId(2)
      const layer = viewer.imageryLayers.addImageryProvider(provider)
      applyFarfieldTone(layer, true)
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

/**
 * 远景色调调整（2026-09-22 用户反馈"颜色差距很大"）。
 *
 * 地块纹理是 Google 影像，在线远景是 ion/Bing 影像——实测同一块场址
 * Bing 的 HSV 饱和度均值 62.8、Google 只有 27.7，Bing 均值 RGB 165/148/124 还偏暖，
 * 交界处就是一圈明显的色差。Cesium 的 ImageryLayer 支持渲染时调亮度/对比/饱和/伽马，
 * 这里用环境变量把"非 Google 源"的远景往地块的色调上拽。
 * 本地远景（同源 Google）不需要调，所以 desaturate=false。
 */
function applyFarfieldTone(layer, desaturate) {
  const tune = (name, fallback) => {
    const value = parseFloat(import.meta.env[name])
    return Number.isFinite(value) ? value : fallback
  }
  layer.brightness = desaturate ? tune('VITE_FARFIELD_BRIGHTNESS', 1) : 1
  layer.contrast = desaturate ? tune('VITE_FARFIELD_CONTRAST', 1) : 1
  layer.saturation = desaturate ? tune('VITE_FARFIELD_SATURATION', 1) : 1
  layer.gamma = desaturate ? tune('VITE_FARFIELD_GAMMA', 1) : 1
  layer.hue = 0
}

/** 加载 public/farfield/manifest.json 描述的本地远景瓦片金字塔；没有就返回 null。 */
async function addLocalFarfield(viewer) {
  try {
    const response = await fetch('/farfield/manifest.json', { cache: 'no-cache' })
    if (!response.ok) return null
    const manifest = await response.json()
    const rect = manifest.rectangle || {}
    const provider = new Cesium.UrlTemplateImageryProvider({
      url: manifest.urlTemplate || '/farfield/{z}/{x}_{y}.jpg',
      rectangle: Cesium.Rectangle.fromDegrees(rect.west, rect.south, rect.east, rect.north),
      minimumLevel: manifest.minimumLevel ?? 0,
      maximumLevel: manifest.maximumLevel ?? 18,
      credit: manifest.credit || '',
    })
    const layer = viewer.imageryLayers.addImageryProvider(provider)
    applyFarfieldTone(layer, false)
    return 'local'
  } catch (error) {
    console.warn('[cesium] 本地远景层读取失败：', error?.message || error)
    return null
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
