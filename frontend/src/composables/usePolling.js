import { onBeforeUnmount, onMounted } from 'vue'

/**
 * 轮询刷新：挂载时立刻取一次，之后每 `intervalMs` 毫秒取一次，卸载时清掉定时器。
 *
 * 抽出来是因为项目里已经有三处一模一样的写法（`HomeView` 30s / `DeviceView` 10s /
 * `ScreenView` 10s），本轮一次又要新增三处（三个角色工作台）——六份复制粘贴，
 * 漏掉一处 `onBeforeUnmount` 就是一条常驻定时器。
 *
 * 现有的两个手写处（`DeviceView` / `ScreenView`）本轮**不动**：它们能正常工作，
 * 顺手改是没必要的回归面。下次碰到时再换。
 *
 * 周期怎么定：**与后端对应的产出周期同量级**，不追求比后端更快。汇总统计是 30s、
 * 设备离线扫描是 10s，所以那些页面就取 30s / 10s——比后端快没有意义，只会白刷接口。
 *
 * @param {() => void | Promise<void>} load 取数函数。**它自己负责把错误显示到页面上**
 *   （本项目各页的惯例是：axios 拦截器统一弹错，页内再留一个 errorMsg 兜底）。
 *   这里额外 catch 一道，是为了不让一次失败变成未处理的 Promise rejection 刷满控制台。
 * @param {number} intervalMs 轮询间隔（毫秒）
 */
export function usePolling(load, intervalMs) {
  let timer = null

  const tick = () => {
    Promise.resolve()
      .then(load)
      .catch(() => {
        // 故意吞掉：错误展示归 load 自己管（见上）。这里再抛就是未处理拒绝。
      })
  }

  onMounted(() => {
    tick()
    timer = setInterval(tick, intervalMs)
  })

  onBeforeUnmount(() => {
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
  })
}
