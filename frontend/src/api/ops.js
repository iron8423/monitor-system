import http from './http'

/**
 * 系统运维接口（`com.monitor.ops.OpsController`，仅 ADMIN）。
 *
 * 四个端点都是**只读**：状态、迁移、数据规模、运行配置。页面拿它们做展示，
 * 不提供任何写操作——备份/清理/重跑迁移这类动作留在命令行，那里的失败代价更低。
 */

/** GET /api/v1/ops/status → 服务 / JVM / 数据库 / 连接池 / SSE 连接数 */
export function opsStatus() {
  return http.get('/v1/ops/status')
}

/** GET /api/v1/ops/migrations → Flyway 已应用与待执行的迁移 */
export function opsMigrations() {
  return http.get('/v1/ops/migrations')
}

/**
 * GET /api/v1/ops/stats → 关键表行数与测量时间跨度。
 * @param {boolean} exact 精确计数（P2-9）：默认走近似值 + 60 秒缓存；
 *   亿级表上 COUNT(*) 会慢，所以精确计数由用户显式触发一次。
 */
export function opsStats(exact = false) {
  return http.get('/v1/ops/stats', { params: exact ? { exact: true } : {} })
}

/** GET /api/v1/ops/config → 运行时开关（密钥只回显"是否默认值"，不回显内容） */
export function opsConfig() {
  return http.get('/v1/ops/config')
}
