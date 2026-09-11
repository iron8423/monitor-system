import { createRouter, createWebHistory } from 'vue-router'

import { useUserStore } from '@/stores/user'

const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    path: '/',
    component: () => import('@/layout/AppLayout.vue'),
    redirect: '/home',
    children: [
      {
        path: 'home',
        name: 'home',
        component: () => import('@/views/HomeView.vue'),
        meta: { title: '总览', icon: 'Odometer' },
      },
      {
        path: 'points',
        name: 'points',
        component: () => import('@/views/PointsView.vue'),
        meta: { title: '测点与曲线', icon: 'DataLine' },
      },
      {
        path: 'devices',
        name: 'devices',
        component: () => import('@/views/DeviceView.vue'),
        meta: { title: '设备状态', icon: 'Cpu' },
      },
      {
        path: 'alarms',
        name: 'alarms',
        component: () => import('@/views/AlarmView.vue'),
        meta: { title: '告警中心', icon: 'Bell' },
      },
      {
        path: 'admin',
        name: 'admin',
        component: () => import('@/views/AdminView.vue'),
        meta: { title: '管理端', icon: 'Setting', roles: ['ADMIN'] },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { public: true, title: '页面不存在' },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  const userStore = useUserStore()
  const title = to.meta?.title
  document.title = title ? `${title} · 通用监测管理系统` : '通用监测管理系统'

  if (to.meta?.public) {
    // 已登录还去登录页 → 直接进工作台
    if (to.name === 'login' && userStore.isLoggedIn) {
      return { path: '/' }
    }
    return true
  }

  if (!userStore.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  // 角色门禁。注意这**只是界面收口**：真正的边界在后端 @PreAuthorize，
  // 前端藏掉入口是为了不让用户点进去看一屏 403，不是安全措施。
  const roles = to.meta?.roles
  if (roles?.length && !roles.includes(userStore.role)) {
    return { path: '/home' }
  }

  return true
})

export default router
