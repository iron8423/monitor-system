import { defineStore } from 'pinia'

import { fetchMe, login as loginApi, logout as logoutApi } from '@/api/auth'
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
  },
})
