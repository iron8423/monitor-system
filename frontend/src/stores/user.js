import { defineStore } from 'pinia'

import {
  changePassword as changePasswordApi,
  fetchMe,
  login as loginApi,
  logout as logoutApi,
  register as registerApi,
  updateProfile as updateProfileApi,
} from '@/api/auth'
import { clearAuth, getToken, getUser, setToken, setUser } from '@/utils/token'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: getToken(),
    user: getUser(),
  }),

  getters: {
    isLoggedIn: (state) => Boolean(state.token),
    displayName: (state) => state.user?.displayName || state.user?.username || '未登录',
    role: (state) => state.user?.role || '',
    roleLabel: (state) => state.user?.roleLabel || state.user?.role || '',
    isAdmin: (state) => state.user?.role === 'ADMIN',
  },

  actions: {
    async login({ username, password }) {
      const data = await loginApi({ username, password })
      this.token = data.token
      this.user = data.user
      setToken(data.token)
      setUser(data.user)
      return data
    },

    /** 刷新当前用户（同时可当作「token 是否还有效」的探针） */
    async loadCurrentUser() {
      const user = await fetchMe()
      this.user = user
      setUser(user)
      return user
    },

    async logout() {
      try {
        // 先让服务端作废令牌，再清本地——顺序反了就等于没调。
        await logoutApi()
      } catch {
        // 服务端不可达时本地照常登出：界面必须先退出，令牌是否已作废由服务端负责。
      }
      this.token = ''
      this.user = null
      clearAuth()
    },

    /** 自助注册（注册即登录）：成功后直接写入令牌，不必再走一次登录 */
    async register(payload) {
      const data = await registerApi(payload)
      this.token = data.token
      this.user = data.user
      setToken(data.token)
      setUser(data.user)
      return data
    },

    /** 本人改自己的资料（姓名/公司/岗位/电话/邮箱）；角色与启用状态不在这条路上 */
    async updateProfile(payload) {
      const user = await updateProfileApi(payload)
      this.user = user
      setUser(user)
      return user
    },

    /**
     * 修改本人密码。成功后**换用返回的新令牌**：
     * 服务端递增了令牌版本（其余端全部下线），当前会话拿的是重签的那一张。
     * 只清空本地而不换令牌，页面会在下一个请求上 401——用户只会看到「改完密码就掉线」。
     */
    async changePassword({ oldPassword, newPassword }) {
      const data = await changePasswordApi({ oldPassword, newPassword })
      this.token = data.token
      this.user = data.user
      setToken(data.token)
      setUser(data.user)
      return data
    },
  },
})
