import { toWindow } from '@/cesium/labelLayout'

/*
 * 标签引线的**屏幕空间叠加层**（2026-09-23 第四轮）。
 *
 * 为什么不用 Cesium 的 polyline 画引线（实测踩到的坑，记下来免得以后重走）：
 *   标签避让会把测点名顶高两三百像素，需要一条引线把它连回自己的点位。
 *   第一版用 `viewer.entities` 的 polyline，几何是对的（把两个端点投影回屏幕，
 *   正好落在"点位"和"标签"上），但**屏幕上一条都看不见**。逐一排除后确定是
 *   **被地形/建筑遮挡**：这条线从地面的点位伸向高处，演示场址又是个山坡，
 *   线段大部分走到山体深度之后；而同一时刻手加的、位于山谷里的对照线正常显示。
 *   换成"沿同一视线把远端拉近"能减轻，但没有保证。
 *
 *   而**测点本身的水滴、圆点、标签都设了 `disableDepthTestDistance: Infinity`**
 *   —— 也就是"永远画在最上层"。引线作为这些元素的附属，用同一套策略才自洽：
 *   直接在本图层用 2D canvas 画，永远压在最上面，和标签的相对位置永远对得上。
 *
 * 代价是它不参与 3D 遮挡（本来也不想参与），并且每帧重绘——只有十来条线段，忽略不计。
 */

export function createLabelOverlay(viewer) {
  const canvas = document.createElement('canvas')
  canvas.className = 'label-overlay'
  canvas.style.cssText =
    'position:absolute;inset:0;pointer-events:none;z-index:5'
  viewer.container.appendChild(canvas)
  const ctx = canvas.getContext('2d')

  /** @type {() => Array<{world: *, dx: number, dy: number, color: string, width?: number}>} */
  let provider = () => []
  let dpr = 1
  let cw = 0
  let ch = 0
  /** 诊断计数：frames 证明监听器有没有被调用，projected 说明有多少线段投影成功 */
  const stats = { frames: 0, requested: 0, projected: 0, drawn: 0 }

  function resize() {
    dpr = window.devicePixelRatio || 1
    cw = viewer.scene.canvas.clientWidth
    ch = viewer.scene.canvas.clientHeight
    const w = Math.max(1, Math.round(cw * dpr))
    const h = Math.max(1, Math.round(ch * dpr))
    if (canvas.width !== w || canvas.height !== h) {
      canvas.width = w
      canvas.height = h
      canvas.style.width = `${cw}px`
      canvas.style.height = `${ch}px`
    }
  }

  const remove = viewer.scene.postRender.addEventListener(() => {
    stats.frames += 1
    resize()
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.clearRect(0, 0, cw, ch)
    const segments = provider() || []
    stats.requested = segments.length
    stats.projected = 0
    stats.drawn = 0
    for (const seg of segments) {
      const start = toWindow(viewer.scene, seg.world)
      if (!start) continue
      stats.projected += 1
      const x2 = start.x + (seg.dx || 0)
      const y2 = start.y + (seg.dy || 0)
      // 位移太小就不画：免得近景在每个点旁边挂一条看不出来的短线
      if (Math.abs(x2 - start.x) < 3 && Math.abs(y2 - start.y) < 8) continue
      ctx.strokeStyle = seg.color || 'rgba(255,255,255,0.55)'
      ctx.lineWidth = seg.width || 1.2
      ctx.beginPath()
      ctx.moveTo(start.x, start.y)
      ctx.lineTo(x2, y2)
      ctx.stroke()
      stats.drawn += 1
    }
  })

  return {
    setProvider(fn) {
      provider = typeof fn === 'function' ? fn : () => []
    },
    stats() {
      return { ...stats }
    },
    destroy() {
      remove()
      canvas.remove()
    },
  }
}
