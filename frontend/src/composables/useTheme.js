import { computed, ref } from 'vue'

/**
 * 白天 / 黑夜两套主题。
 *
 * 实现方式刻意只有一条通路：**在 <html> 上打两个标记**——
 *   · `data-theme="light|dark"` —— 我们自己写的 CSS 变量（工作台外壳、3D 大屏 HUD）认它；
 *   · `class="dark"`            —— Element Plus 官方的暗色变量表认它（main.js 里已引入）。
 *
 * 为什么不给每个组件传 prop：主题是全局态，传 prop 会让"哪些组件参与主题"变成一张需要
 * 维护的清单，而漏掉一个组件的表现是**局部还是亮的**——那种 bug 很难在 review 里看出来。
 * 一个属性 + 一组变量，新增组件默认就跟着走。
 *
 * 默认值：先看用户上次的选择（localStorage），没有就跟随操作系统
 * （`prefers-color-scheme`）——值班室里两种习惯的人都有，跟着系统走最不打扰。
 */

const STORAGE_KEY = 'monitor_theme'
const THEMES = ['light', 'dark']

function readStored() {
  try {
    const value = localStorage.getItem(STORAGE_KEY)
    return THEMES.includes(value) ? value : null
  } catch {
    // 隐私模式/被禁用时 localStorage 会抛异常：主题不值得让整个应用起不来
    return null
  }
}

function systemPrefers() {
  try {
    return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
  } catch {
    return 'light'
  }
}

const theme = ref(readStored() || systemPrefers())

function apply(next) {
  const root = document.documentElement
  root.dataset.theme = next
  // Element Plus 的暗色变量表挂在 .dark 上，两个标记必须同时切，少一个就会出现
  // 「我们的面板暗了、el-table 还是白的」这种半截状态
  root.classList.toggle('dark', next === 'dark')
  // 浏览器原生控件（滚动条、表单）跟着走
  root.style.colorScheme = next
}

apply(theme.value)

/** 应用启动时调用一次：把已存/系统偏好的主题落到 DOM 上（模块加载时已 apply，这里是显式入口） */
export function initTheme() {
  apply(theme.value)
}

export function useTheme() {
  const isDark = computed(() => theme.value === 'dark')

  function setTheme(next) {
    if (!THEMES.includes(next) || next === theme.value) return
    theme.value = next
    apply(next)
    try {
      localStorage.setItem(STORAGE_KEY, next)
    } catch {
      /* 存不住就只对本次会话生效，不报错 */
    }
  }

  function toggleTheme() {
    setTheme(theme.value === 'dark' ? 'light' : 'dark')
  }

  return { theme, isDark, setTheme, toggleTheme }
}
