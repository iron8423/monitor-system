<script setup>
import { useRouter } from 'vue-router'

import { useUserStore } from '@/stores/user'

/**
 * 兜底落地页：登录成功、但角色认不出来。
 *
 * 两种来源，都由路由守卫把用户送到这里：
 *   ① localStorage 里只剩 token、user 对象丢了（`role` 为 ''），
 *      此时守卫认不出该派到哪个工作台（见 `router/index.js` 的 `homeOf`）；
 *   ② 后端出现了一个前端还不认识的新角色。
 *
 * **刻意不写 `meta.roles`**：带了就会被守卫当成一个受限页面，而用户此时的角色
 * 又匹配不上，于是又弹回这里——守卫级死循环。生产构建下 vue-router 的
 * 「30 次导航」保护会被摇掉，那是直接卡死标签页。
 *
 * 也不能拿 `/screen` 兜底：它不套 AppLayout，进来布局整个卸载（没有菜单），
 * 而大屏自己的「退出大屏」又 push 回 `/home`，用户就困在大屏里出不来了。
 */

defineOptions({ name: 'HomeNone' })

const router = useRouter()
const userStore = useUserStore()

async function relogin() {
  await userStore.logout()
  router.push({ name: 'login' })
}
</script>

<template>
  <div class="page">
    <div class="mk-panel">
      <div class="mk-panel-title">未分配角色</div>
      <div class="body">
        <p>
          当前账号
          <span class="mk-mono">{{ userStore.displayName }}</span>
          登录成功了，但没有可用的角色{{ userStore.role ? `（收到的是「${userStore.role}」）` : '' }}，
          因此不知道该进哪个工作台。
        </p>
        <p class="mk-muted">
          常见原因是浏览器本地缓存里只剩登录凭证、用户信息丢了。重新登录一次通常会恢复；
          若仍然如此，说明后端给这个账号配的角色前端还不认识，需要管理员核对账号权限。
        </p>
        <div class="actions">
          <el-button type="primary" @click="relogin">重新登录</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.body {
  padding: 16px;
  font-size: 13px;
  line-height: 1.9;
}

.body p {
  margin: 0 0 8px;
}

.actions {
  margin-top: 16px;
}
</style>
