import http from './http'

/** POST /api/v1/auth/login → { token, user } */
export function login(payload) {
  // silent：登录失败由登录页自己提示，避免全局拦截器弹一次、页面再弹一次
  return http.post('/v1/auth/login', payload, { silent: true })
}

/** POST /api/v1/auth/logout（无状态 JWT，服务端不做会话销毁） */
export function logout() {
  return http.post('/v1/auth/logout', null, { silent: true })
}

/** GET /api/v1/auth/me → UserVO */
export function fetchMe() {
  return http.get('/v1/auth/me')
}
