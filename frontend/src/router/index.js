import { createRouter, createWebHistory } from 'vue-router'
import { ElMessageBox } from 'element-plus'

import { useUserStore } from '@/stores/user'

/**
 * 角色 → 落地页。**这是唯一数据源**：下面每个路由的 `name` 与 `meta.roles` 都从它派生。
 *
 * 为什么强调「唯一」：守卫在角色门禁不通过时会把人送到 `homeOf(role)`。若这张表的
 * `name` 与该路由 `meta.roles` 写得不一致（比如表里 OPERATOR 指向一个 roles: ['ADMIN']
 * 的路由），守卫就会把用户弹到他正待着的那个页面——**守卫级无限重定向**。而 vue-router
 * 的「30 次导航」保护整段包在 `process.env.NODE_ENV !== 'production'` 里，**生产构建下被
 * 摇掉**，结果是微任务无限递归、标签页卡死而不是报错。派生而非手写，就不存在失配。
 */
const ROLE_HOMES = [
  { role: 'ADMIN', name: 'home-admin', title: '管理工作台', path: 'home/admin', component: () => import('@/views/home/HomeAdmin.vue') },
  { role: 'OPERATOR', name: 'home-operator', title: '值班工作台', path: 'home/operator', component: () => import('@/views/home/HomeOperator.vue') },
  { role: 'ANALYST', name: 'home-analyst', title: '研判工作台', path: 'home/analyst', component: () => import('@/views/home/HomeAnalyst.vue') },
  { role: 'MAINTAINER', name: 'home-maintainer', title: '运维工作台', path: 'home/maintainer', component: () => import('@/views/home/HomeMaintainer.vue') },
]

const HOME_BY_ROLE = Object.fromEntries(ROLE_HOMES.map((h) => [h.role, h.name]))

/**
 * 认不出的角色（token 在、user 丢了，或后端将来加了新角色）落到这里。
 *
 * 兜底页**必须在 AppLayout 内且不带 `meta.roles`**，不能用 `/screen`：大屏是顶层路由、
 * 不套布局，用户点侧栏「工作台」会被送到那儿，布局整个卸载、没有菜单，而大屏上的
 * 「退出大屏」又 `push('/home')` 跳回来——用户在页面里出不来，只能改地址栏。
 */
const FALLBACK_HOME = 'home-none'

const homeOf = (role) => HOME_BY_ROLE[role] || FALLBACK_HOME

const roleHomeRoutes = ROLE_HOMES.map((h) => ({
  path: h.path,
  name: h.name,
  component: h.component,
  meta: { title: h.title, icon: 'Odometer', roles: [h.role], menuPath: '/home' },
}))

const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    // 自助注册：与登录同为 public（还没有会话就要能访问）。
    // 注册成功直接进系统，所以这里不套 AppLayout 之外的任何守卫逻辑。
    path: '/register',
    name: 'register',
    component: () => import('@/views/RegisterView.vue'),
    meta: { public: true, title: '注册' },
  },
  {
    // 3D 大屏（阶段 3）：独立于工作台布局，占满整屏、自带 HUD，不套侧边菜单
    path: '/screen',
    name: 'screen',
    component: () => import('@/views/ScreenView.vue'),
    meta: { title: '三维大屏', icon: 'Location' },
  },
  {
    path: '/',
    component: () => import('@/layout/AppLayout.vue'),
    redirect: '/home',
    children: [
      {
        path: 'home',
        name: 'home',
        meta: { title: '工作台', icon: 'Odometer', menuPath: '/home' },
        // 这里**必须用 beforeEnter，不能写成 redirect**。
        //
        // route-level redirect 在全局 beforeEach **之前**求值
        // （vue-router: pushWithRedirect 里先 handleRedirectRecord、之后才 navigate 跑守卫）。
        // 若用 redirect：未登录用户访问 / 或 /home 时，会先被按 role='' 弹到兜底页，
        // 守卫拿到的 to 已经是兜底页，于是拼出 `login?redirect=/home/none`——
        // 登录后就永远落在兜底页而不是自己的工作台。beforeEnter 在 beforeEach **之后**
        // 执行，此时 to.fullPath 还是用户真正输入的 /home，`login?redirect` 天然正确。
        beforeEnter: () => ({ name: homeOf(useUserStore().role) }),
      },
      ...roleHomeRoutes,
      {
        path: 'home/none',
        name: FALLBACK_HOME,
        component: () => import('@/views/home/HomeNone.vue'),
        // 注意**不带 roles**：它就是给「角色认不出来」的人看的，带了就又弹回去了
        meta: { title: '未分配角色', menuPath: '/home' },
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
        // roles 与侧边栏菜单表（AppLayout 的 menus）保持一致：菜单藏了、路由也要挡，
        // 否则「直接输地址」和「点菜单」会给出两种结果
        meta: { title: '设备状态', icon: 'Cpu', roles: ['ADMIN', 'MAINTAINER', 'OPERATOR'] },
      },
      {
        path: 'alarms',
        name: 'alarms',
        component: () => import('@/views/AlarmView.vue'),
        meta: { title: '告警中心', icon: 'Bell' },
      },
      {
        path: 'media',
        name: 'media',
        component: () => import('@/views/MediaView.vue'),
        meta: { title: '影像挂点', icon: 'Picture', roles: ['ADMIN', 'MAINTAINER', 'ANALYST'] },
      },
      {
        path: 'admin',
        name: 'admin',
        component: () => import('@/views/AdminView.vue'),
        meta: { title: '管理端', icon: 'Setting', roles: ['ADMIN'] },
      },
      {
        // 审计日志：`roles: ['ADMIN']` 与后端 `AuditLogController` 的**类级**
        // `@PreAuthorize("hasRole('ADMIN')")` 同口径。这里只是不让人点进来，
        // 真正的边界在后端——非管理员直达这条路由会被守卫弹回，就算绕过守卫，
        // 接口仍然是 403（两层都拦，但只有后者是安全边界）。
        path: 'audit',
        name: 'audit',
        component: () => import('@/views/AuditView.vue'),
        meta: { title: '审计日志', icon: 'Document', roles: ['ADMIN'] },
      },
      {
        // 个人中心：**所有登录用户都有**，所以刻意不带 `meta.roles`
        // （带了就只有列进去的角色能进，而「看自己的资料」不该分岗位）。
        // 入口在顶栏头像下拉里，不进侧边栏——侧边栏列的是"页面"，
        // 而这是"关于我"的一块，放在账号旁边才是它该在的位置。
        path: 'profile',
        name: 'profile',
        component: () => import('@/views/ProfileView.vue'),
        meta: { title: '个人中心' },
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

// 更新部署后，已打开的页面可能仍引用被替换的旧分包。
// 不自动循环刷新；让用户明确选择重新加载原目标地址，并保留原始错误供排查。
let navigationErrorVisible = false
router.onError((error, to) => {
  console.error('[router] 页面加载失败', to?.fullPath, error)
  if (navigationErrorVisible) return
  navigationErrorVisible = true
  const target = router.resolve(to?.fullPath || '/home').href
  ElMessageBox.confirm(
    '目标页面未能加载，可能是系统更新后页面资源已更换，或网络连接中断。请重新加载；若仍失败，请记录浏览器控制台中的第一条错误。',
    '页面加载失败',
    { confirmButtonText: '重新加载目标页面', cancelButtonText: '暂不重试', type: 'error', closeOnClickModal: false },
  ).then(() => window.location.assign(target))
    .catch(() => {})
    .finally(() => { navigationErrorVisible = false })
})

// 路由名写错不是「静默 404」而是**抛异常**（matcher 取不到 name 直接 throw），
// 且它在同步路径上抛出——而 `app.use(router)` 的首次导航是 `push().catch()`，
// 同步 throw 穿不进 .catch，会直接打断安装、整站白屏。这里提前拦一道。
if (import.meta.env.DEV) {
  for (const name of [...Object.values(HOME_BY_ROLE), FALLBACK_HOME]) {
    console.assert(router.hasRoute(name), `[router] 落地页路由名不存在: ${name}`)
  }
}

router.beforeEach((to) => {
  const userStore = useUserStore()
  const title = to.meta?.title
  document.title = title ? `${title} · 通用监测管理系统` : '通用监测管理系统'

  if (to.meta?.public) {
    // 已登录还去登录页 → 直接进自己的工作台（不是 /home，省一次跳转）
    if (to.name === 'login' && userStore.isLoggedIn) {
      return { name: homeOf(userStore.role) }
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
    const target = homeOf(userStore.role)
    // 逃生舱：绝不把用户弹回他正待着的页面。真出现 `homeOf` 与 meta.roles 失配
    // （比如以后有人手工加了条路由却没从 ROLE_HOMES 派生），那就是守卫级死循环，
    // 而 vue-router 的 30 次保护只在 dev 生效，生产会卡死标签页。
    if (to.name === target) {
      return { name: FALLBACK_HOME }
    }
    return { name: target }
  }

  return true
})

export default router
