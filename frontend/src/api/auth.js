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

/**
 * POST /api/v1/auth/password → { token, user }
 *
 * 成功后服务端会把该用户的令牌版本 +1（= 别处签发的令牌全部作废），
 * 并用**新版本**重签一张给当前会话，所以这里必须把返回的 token 落回本地，
 * 否则下一个请求就 401 了——「改完密码页面自己掉线」就是这么来的。
 */
export function changePassword(payload) {
  // silent：口令错误、新旧相同这些都是表单自己的事，不该再弹一次全局提示
  return http.post('/v1/auth/password', payload, { silent: true })
}
