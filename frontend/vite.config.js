import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  // 开发期与预览期都用同一份代理规则，避免「dev 能通、preview 不通」
  preview: {
    port: 4173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  server: {
    port: 5173,
    open: false,
    // 后端 SecurityConfig 没有配置 CORS，所以开发期统一走 Vite 代理：
    // 浏览器请求同源的 /api/**，由 dev server 转发到 localhost:8080，天然无跨域。
    // 好处：① 不用改后端；② 生产部署时前端 dist 由 Nginx/后端同源托管，路径口径一致。
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // SSE（/api/v1/stream）走同一个代理；关闭压缩缓冲，否则事件会被攒着一起推
        ws: false,
      },
    },
  },
})
