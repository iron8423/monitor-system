import * as Cesium from 'cesium'

/**
 * 渲染档位（2026-09-20 试验：把「受光渲染」搬进真实大屏）。
 *
 * 为什么做成档位而不是直接改默认值：现在这套大屏的观感是"深色底 + 均匀贴图"，
 * 换成受光渲染会**改变所有场景的画面**（含桥梁/铁路/工厂三个演示场景），
 * 属于会影响交付观感的改动。所以用环境变量开关，默认仍是 `flat`（与历史一致），
 * 要看效果就 `VITE_RENDER_MODE=lit`。
 *
 * 配套的一条：受光模式需要**不烘焙阴影的资产**（`--material lit --baked-shade 0`
 * 生成的 GLB），否则纹理里的山体阴影会和实时日照叠成两层。
 * 资产的切换用 `VITE_ASSET_OVERRIDE` 临时指向新资产，不动数据库迁移。
 */

const env = import.meta.env

export const RENDER_MODE = String(env.VITE_RENDER_MODE || 'flat').toLowerCase()
export const LIT_RENDERING = RENDER_MODE === 'lit'
/** 临时替换场景资产（只是本地预览；真正上线要新版本号迁移 + 更新 asset_sha256）。 */
export const ASSET_OVERRIDE = String(env.VITE_ASSET_OVERRIDE || '')
/** 固定太阳时刻（UTC）：北京时间 15:30 的侧光，投影长、坡面不至于黑掉。 */
const SUN_TIME = String(env.VITE_SUN_TIME || '2026-09-20T07:30:00Z')

export function applyRenderProfile(viewer) {
  if (!viewer || !LIT_RENDERING) return
  const scene = viewer.scene

  // 固定时刻 —— 光照是"布景"，不该随真实时间变（晚上打开大屏就全黑了）。
  viewer.clock.currentTime = Cesium.JulianDate.fromIso8601(SUN_TIME)
  viewer.clock.shouldAnimate = false

  // 只让模型吃光照：椭球（背景）不参与日照，否则背景会被照亮成灰蓝、
  // 还会接住山体投影长出一块暗斑。天空盒/大气同理，关掉才能保持深色底。
  scene.globe.enableLighting = false
  scene.globe.show = false
  scene.skyBox.show = false
  scene.skyAtmosphere.show = false
  scene.sun.show = false
  scene.backgroundColor = Cesium.Color.fromCssColorString('#0b1622')

  scene.shadowMap.enabled = true
  scene.shadowMap.softShadows = true
  scene.shadowMap.darkness = 0.28
  scene.postProcessStages.fxaa.enabled = true

  // HDR / 环境光遮蔽 / 泛光依赖浮点渲染目标，部分环境（软件渲染）会整屏变黑。
  // 所以默认关，用 VITE_HDR=1 / VITE_AO=1 / VITE_BLOOM=1 单独试。
  scene.highDynamicRange = env.VITE_HDR === '1'
  if (env.VITE_AO === '1' && scene.postProcessStages.ambientOcclusion) {
    scene.postProcessStages.ambientOcclusion.enabled = true
  }
  if (env.VITE_BLOOM === '1') {
    scene.postProcessStages.bloom.enabled = true
  }
}
