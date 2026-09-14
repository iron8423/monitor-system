<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'

import { openStream } from '@/api/monitor'
import { useUserStore } from '@/stores/user'

defineOptions({ name: 'AppLayout' })

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const collapsed = ref(false)

// 菜单 = 阶段 2~5 的交付清单。已实现的能点，未实现的 disabled，避免点了白页。
const menus = [
  // 四个角色共用这一个入口，由 `/home` 按角色派发到各自的工作台
  { path: '/home', title: '工作台', icon: 'Odometer', ready: true },
  { path: '/points', title: '测点与曲线', icon: 'DataLine', ready: true },
  { path: '/devices', title: '设备状态', icon: 'Cpu', ready: true },
  { path: '/screen', title: '3D 大屏', icon: 'Location', ready: true },
  { path: '/alarms', title: '告警中心', icon: 'Bell', ready: true },
  { path: '/media', title: '影像挂点', icon: 'Picture', ready: true },
  { path: '/admin', title: '管理端', icon: 'Setting', ready: true, roles: ['ADMIN'] },
]

// 菜单里的角色收口只是界面引导，真正的边界在后端 @PreAuthorize
const visibleMenus = computed(() =>
  menus.filter((m) => !m.roles?.length || m.roles.includes(userStore.role)),
)

const currentTitle = computed(() => route.meta?.title || '')

/**
 * 全局 SSE（契约 D8）：只在这里开**一条**连接并保持住。
 *
 * 放在布局而不是各页面里，是因为页面组件会随路由切换销毁——
 * 每页各开一条的话，切一次路由就断一次，断开期间的事件直接丢掉，
 * 而事件本身没有重放机制。布局是唯一稳定存活的那一层。
 */
const live = ref(false)
let stream = null

function connectStream() {
  if (!userStore.token || stream) return
  // token 走 query：EventSource 发不了请求头，这是契约给 SSE 开的唯一例外
  stream = openStream(userStore.token)
  stream.addEventListener('open', () => (live.value = true))
  stream.addEventListener('error', () => (live.value = false))
  // 事件内容由各页面自己订阅；这里只需要知道「连接活着」，
  // 顺便把连接建立起来，页面挂载时订阅就不会漏掉开头的事件。
}

/*
 * 整页卸载（F5、直接输地址、跳外链）时 onBeforeUnmount **不会执行**——文档是被丢弃的，
 * Vue 没有机会做清理。而这是一条长连接：nginx 非缓冲反代只能靠「向客户端写失败」察觉
 * 对方已走，而第一次写进半关闭的 socket 会成功，所以要等**第二次**心跳（30s×2）才收尾。
 * 这段时间里那条半关闭的 socket 仍占着浏览器「单源 6 连接」的名额——连刷 6 次，
 * 此后所有请求全部排队，界面看起来就是死了几十秒。
 * 显式 close 让浏览器当场回收这个名额，不必等对端。
 */
function onPageShow(event) {
  // 从 bfcache 回来时 pagehide 已经关过连接了，这里必须补上，
  // 否则用户按一次「后退」SSE 就**静默地**再也不来——页面看起来一切正常，
  // 只是数据永远停在那一刻，这是最难查的一类故障
  if (event.persisted) connectStream()
}

onMounted(() => {
  connectStream()
  window.addEventListener('pagehide', disconnectStream)
  window.addEventListener('pageshow', onPageShow)
})

function disconnectStream() {
  stream?.close()
  stream = null
}

onBeforeUnmount(() => {
  window.removeEventListener('pagehide', disconnectStream)
  window.removeEventListener('pageshow', onPageShow)
  disconnectStream()
})

async function handleCommand(command) {
  if (command !== 'logout') return
  try {
    await ElMessageBox.confirm('确认退出登录？', '提示', { type: 'warning' })
  } catch {
    return
  }
  await userStore.logout()
  ElMessage.success('已退出登录')
  router.replace('/login')
}
</script>

<template>
  <el-container class="layout">
    <el-header class="header">
      <div class="header-left">
        <el-icon class="collapse-btn" @click="collapsed = !collapsed">
          <component :is="collapsed ? 'Expand' : 'Fold'" />
        </el-icon>
        <div class="logo">
          <span class="logo-mark">UGMS</span>
          <span class="logo-text">通用监测管理系统</span>
        </div>
        <el-divider direction="vertical" />
        <span class="crumb">{{ currentTitle }}</span>
      </div>

      <div class="header-right">
        <el-tag :type="live ? 'success' : 'info'" effect="plain" size="small">
          {{ live ? '实时已连接' : '实时未连接' }}
        </el-tag>
        <el-dropdown trigger="click" @command="handleCommand">
          <span class="user">
            <el-avatar :size="28" class="user-avatar">{{ userStore.displayName.slice(0, 1) }}</el-avatar>
            <span class="user-name">{{ userStore.displayName }}</span>
            <el-tag v-if="userStore.roleLabel !== userStore.displayName" size="small" effect="plain">
              {{ userStore.roleLabel }}
            </el-tag>
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item disabled>
                账号：{{ userStore.user?.username }}
              </el-dropdown-item>
              <el-dropdown-item divided command="logout">
                <el-icon><SwitchButton /></el-icon>退出登录
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </el-header>

    <el-container class="body">
      <el-aside :width="collapsed ? '64px' : '210px'" class="aside">
        <!--
          active 认 `meta.menuPath` 而不是 `route.path`：四个角色的工作台是
          /home/operator 这类**子路径**，而菜单 index 只有一个 `/home`，
          直接绑 route.path 的话进了工作台菜单就不高亮了。
          （menuPath 写错不会报错，只是静默不高亮——Element Plus 内部对
          不存在的 activeIndex 是置空处理。改动路由 meta 时留意。）
        -->
        <el-menu :default-active="route.meta?.menuPath || route.path" :collapse="collapsed" class="menu" router>
          <el-menu-item
            v-for="menu in visibleMenus"
            :key="menu.path"
            :index="menu.path"
            :disabled="!menu.ready"
          >
            <el-icon><component :is="menu.icon" /></el-icon>
            <template #title>
              <span class="menu-title">{{ menu.title }}</span>
              <el-tag v-if="!menu.ready" size="small" type="info" effect="plain" class="menu-tag">
                {{ menu.stage }}
              </el-tag>
            </template>
          </el-menu-item>
        </el-menu>
      </el-aside>

      <el-main class="main">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100vh;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 56px;
  padding: 0 18px;
  color: #eaf2ff;
  background: linear-gradient(90deg, var(--mk-dark-1), var(--mk-dark-2));
}

.header-left,
.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.collapse-btn {
  font-size: 18px;
  cursor: pointer;
  opacity: 0.85;
}

.collapse-btn:hover {
  opacity: 1;
}

.logo {
  display: flex;
  gap: 9px;
  align-items: center;
}

.logo-mark {
  padding: 3px 7px;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  color: #06101f;
  background: linear-gradient(135deg, #6fd3ff, #35d0ba);
  border-radius: 4px;
}

.logo-text {
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.03em;
}

.crumb {
  font-size: 13px;
  color: #9fb6d4;
}

.user {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  outline: none;
}

.user-avatar {
  color: #06101f;
  background: #6fd3ff;
}

.user-name {
  font-size: 13px;
  color: #eaf2ff;
}

.body {
  height: calc(100vh - 56px);
}

.aside {
  background: #fff;
  border-right: 1px solid var(--mk-border);
  transition: width 0.2s ease;
}

.menu {
  border-right: none;
}

.menu-title {
  margin-right: 6px;
}

.menu-tag {
  transform: scale(0.85);
}

.main {
  padding: 18px;
  overflow-y: auto;
  background: var(--mk-bg);
}
</style>
