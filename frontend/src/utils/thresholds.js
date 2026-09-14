import { ALARM_LEVEL } from '@/constants/status'

/**
 * 告警规则 → 曲线上的阈值线。
 *
 * 为什么要抽成独立函数：曲线页原来把 ±3mm 写死在组件里（注释也写着「等规则页做出来
 * 再改成拉接口」）。现在规则由管理端维护，画在哪条线上就得跟规则一致——否则会出现
 * 「规则改成 ±5mm 了、图上还画着 3mm」这种自相矛盾的画面。规则 → 线的这层换算
 * 是纯函数，放在这里可以直接跑断言，不必开浏览器。
 *
 * 只取 **THRESHOLD 类型**：`RATE` / `CHANGE` 目前后端也不评估（契约 §4），
 * 画出来等于暗示用户它在生效。
 *
 * 测项**必须精确匹配**：一条 mm 的阈值画到 mm/d 的图上会直接误导
 * （`metricCode` 为空的规则视为「未指定测项」，同样不画）。
 *
 * 点号：`pointId` 为空的规则是全局规则，对所有测点生效；指定了测点的规则
 * 只在该测点亮。同一条线（同值同向）出现多份时，**留更具体的那条**
 * （测点规则优先于全局规则，更高级别优先），避免图上叠出两条一模一样的线。
 *
 * @param {Array} rules GET /api/v1/alarm-rules 的裸数组
 * @param {{pointId?: number|null, metricCode?: string}} ctx 当前看的是哪个测点的哪个测项
 * @returns {Array<{id:number|undefined,label:string,value:number,color:string,level:string}>}
 *          按数值升序，直接喂给 SeriesChart 的 thresholds
 */
export function buildThresholdLines(rules, { pointId = null, metricCode = '' } = {}) {
  const candidates = (rules || [])
    .filter((r) => r && r.enabled !== false)
    .filter((r) => (r.type || 'THRESHOLD') === 'THRESHOLD')
    .filter((r) => r.metricCode && r.metricCode === metricCode)
    .filter((r) => r.pointId == null || r.pointId === pointId)
    .filter((r) => Number.isFinite(Number(r.value)))

  // 具体性排序：测点规则在前（pointId 非空），再按级别由弱到强——
  // 后面「同值只留第一条」时留下的就是更该显示的那条
  const rank = { notice: 0, warning: 1, alarm: 2 }
  candidates.sort((a, b) => {
    const specific = (b.pointId != null ? 1 : 0) - (a.pointId != null ? 1 : 0)
    if (specific !== 0) return specific
    return (rank[b.level] ?? -1) - (rank[a.level] ?? -1)
  })

  const seen = new Set()
  const lines = []
  for (const rule of candidates) {
    const value = Number(rule.value)
    const key = `${rule.operator === 'lte' ? 'lte' : 'gte'}|${value}`
    if (seen.has(key)) continue
    seen.add(key)

    const level = ALARM_LEVEL[rule.level]
    lines.push({
      id: rule.id,
      label: `${level?.label || rule.level || '阈值'} ${value > 0 ? '+' : ''}${value}`,
      value,
      color: level?.color || '#e6a23c',
      level: rule.level,
    })
  }

  return lines.sort((a, b) => a.value - b.value)
}

/** 规则条数摘要（界面上一行提示，免得人以为阈值线永远只有 ±3mm） */
export function describeRules(rules, ctx) {
  const rulesOfMetric = (rules || []).filter(
    (r) => r && r.enabled !== false && r.metricCode === ctx?.metricCode,
  )
  if (!rulesOfMetric.length) {
    return { text: '该测项暂无告警规则', tone: 'warn' }
  }
  const lines = buildThresholdLines(rules, ctx)
  return {
    text: `阈值来自告警规则：${lines.length} 条（测项 ${ctx.metricCode}）`,
    tone: lines.length ? 'ok' : 'warn',
  }
}
