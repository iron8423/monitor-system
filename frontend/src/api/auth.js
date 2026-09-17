import http from './http'

/** POST /api/v1/auth/login → { token, user } */
export function login(payload) {
  // silent：登录失败由登录页自己提示，避免全局拦截器弹一次、页面再弹一次
  return http.post('/v1/auth/login', payload, { silent: true })
}

/**
 * POST /api/v1/auth/logout
 * 服务端会把该用户的令牌版本 +1，作废此前签发的**全部**令牌（含其它端）。
 * 必须在清本地 token **之前**调用：请求要带上那张需要被作废的令牌。
 */
export function logout() {
  return http.post('/v1/auth/logout', null, { silent: true })
}

/** GET /api/v1/auth/me → UserVO */
export function fetchMe() {
  return http.get('/v1/auth/me')
}
