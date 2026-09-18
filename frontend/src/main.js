import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIcons from '@element-plus/icons-vue'

import 'element-plus/dist/index.css'
// Element Plus 的暗色变量表：只在 <html class="dark"> 下生效，见 composables/useTheme.js
import 'element-plus/theme-chalk/dark/css-vars.css'
import './styles/index.css'

import App from './App.vue'
import router from './router'
import { initTheme } from './composables/useTheme'

// 主题要在挂载前落到 <html> 上：晚一步就会先闪一帧亮色（FOUC）
initTheme()

const app = createApp(App)

// 图标全局注册：模板里直接写 <el-icon><DataLine /></el-icon>
for (const [name, component] of Object.entries(ElementPlusIcons)) {
  app.component(name, component)
}

// 顺序：pinia 必须先装，router 守卫里才能拿到 store
app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')
