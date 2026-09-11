import axios from 'axios'
import { ElMessage } from 'element-plus'

import { clearAuth, getToken } from '@/utils/token'

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 15000,
})

/**
 * 请求拦截：统一补 Authorization: Bearer <JWT>。
 * 只有 SSE 例外，它用 ?token= 传（EventSource 发不了 Header），在 stream 模块里单独拼。
 */
http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

/**
 * 响应拦截：拆 A 的统一信封 { code, message, data }。
 * 成功（code === 0）直接把 data 交给调用方，业务代码拿到的就是纯数据。
 */
http.interceptors.response.use(
  (response) => {
    const body = response.data

    // 媒体内容等二进制流原样返回
    if (body instanceof Blob || body instanceof ArrayBuffer || typeof body !== 'object' || body === null) {
      return body
    }

    if (typeof body.code === 'number') {
      if (body.code === 0) {
        return body.data
      }
      const error = new Error(body.message || '请求失败')
      error.code = body.code
      if (!response.config?.silent) {
        ElMessage.error(error.message)
      }
      return Promise.reject(error)
    }

    return body
  },
  (error) => {
    const status = error.response?.status
    const body = error.response?.data

    let message = '请求失败'
    if (body && typeof body === 'object' && body.message) {
      message = body.message
    } else if (error.code === 'ECONNABORTED') {
      message = '请求超时，请确认后端已启动'
    } else if (error.code === 'ERR_NETWORK') {
      message = '连不上后端（http://localhost:8080），请先启动后端服务'
    } else if (status === 401) {
      message = '登录已失效，请重新登录'
    } else if (status === 403) {
      message = '当前角色没有该操作权限'
    } else if (status === 404) {
      message = '资源不存在'
    } else if (status >= 500) {
      message = body?.message || '服务端异常'
    }

    error.message = message
    error.status = status

    // token 失效/未登录：清本地凭证并回登录页。
    // 只排除「登录接口本身」——它返回 401 表示密码错，不该触发跳转/清空；
    // 注意不能图省事排除整个 /auth/**：/auth/me 是受保护接口，token 过期时正是它返回 401，
    // 把它排除掉会导致脏 token 永远清不掉、页面停在半死不活的状态（本文件曾经的 bug）。
    const isLoginRequest = String(error.config?.url || '').includes('/auth/login')
    if (status === 401 && !isLoginRequest) {
      clearAuth()
      if (window.location.pathname !== '/login') {
        const redirect = encodeURIComponent(window.location.pathname + window.location.search)
        window.location.replace(`/login?redirect=${redirect}`)
      }
    }

    if (!error.config?.silent) {
      ElMessage.error(message)
    }
    return Promise.reject(error)
  },
)

export default http
