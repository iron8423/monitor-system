/**
 * 请求代际守卫（清单第 17 条：快速切换测点时防请求串数据）。
 *
 * 语义与 `views/ScreenView.vue` 的 `sceneLoadGeneration` **逐字相同**——进入时自增、
 * 每个 await 之后比对、卸载时再自增一次，**后发的请求获胜，迟到的旧响应一个字都不许写**。
 * 收成一处不是因为写法更好，而是因为 `.vue` 里的内联写法脚本测不到（harness 不加载
 * `.vue`），而这条守卫只活在 `.vue` 里——不抽出来就没有任何断言能覆盖它的语义。
 *
 * 用法：
 *   const guard = createRequestGuard()
 *   async function load() {
 *     const token = guard.next()
 *     const data = await fetchSomething()
 *     if (!guard.isCurrent(token)) return
 *     state.value = data
 *   }
 *   onBeforeUnmount(guard.invalidate)
 *
 * 三处最容易漏，`PointsView` 原先是三处全漏：
 *   - `finally` 里也要判——旧请求的 finally 把**在途**新请求的 loading 关掉，
 *     用户看到的是「加载完了但图还是空的」；
 *   - `catch` 里也要判——旧请求的失败不许把新测点的数据清空；
 *   - 请求参数要**快照**——`guard.next()` 与 `await` 之间读到的 `selectedId.value`
 *     可能已经变了，请求与「它属于哪一代」就对不上。
 */
export function createRequestGuard() {
  let current = 0
  return {
    /** 开始一次请求，返回它的代号。每次调用都让此前所有代号失效 */
    next() {
      current += 1
      return current
    },
    /** 这个代号还是最新的吗（false = 迟到了，不许写回） */
    isCurrent(token) {
      return token === current
    },
    /** 作废所有在途请求（组件卸载时用） */
    invalidate() {
      current += 1
    },
  }
}
