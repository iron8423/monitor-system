/** 后端时间统一是 ISO8601 带 +08:00，这里只做展示层格式化和排序用的解析。 */

export function parseTime(value) {
  if (!value) return null
  const d = new Date(value)
  return Number.isNaN(d.getTime()) ? null : d
}

/** ISO8601 → 'MM-DD HH:mm:ss'（表格里用，省地方） */
export function formatTimeShort(value) {
  const d = parseTime(value)
  if (!d) return '—'
  const pad = (n) => String(n).padStart(2, '0')
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/** ISO8601 → 'YYYY-MM-DD HH:mm:ss' */
export function formatTime(value) {
  const d = parseTime(value)
  if (!d) return '—'
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/** 数字展示：保留 n 位，空值给『—』而不是 0（0 和「没数据」是两回事） */
export function formatNumber(value, digits = 2) {
  if (value === null || value === undefined || value === '') return '—'
  const n = Number(value)
  return Number.isNaN(n) ? '—' : n.toFixed(digits)
}

/** 带正负号的展示（形变正负是物理含义，不能丢符号） */
export function formatSigned(value, digits = 2) {
  if (value === null || value === undefined || value === '') return '—'
  const n = Number(value)
  if (Number.isNaN(n)) return '—'
  return `${n > 0 ? '+' : ''}${n.toFixed(digits)}`
}

/** 相对时间：多久没更新了（判断设备是否在报数很直观） */
export function fromNow(value) {
  const d = parseTime(value)
  if (!d) return '—'
  const diff = Date.now() - d.getTime()
  // ±1 分钟内都算「刚刚」（浏览器与服务器有几秒偏差很正常）
  if (Math.abs(diff) < 60e3) return '刚刚'
  // 时间戳落在未来：设备时钟比服务器快，或者雷达模拟器把模拟时钟推快了
  // （tools/radar_simulator 默认每轮 +30 分钟）。这种要说出来，不能显示成「刚刚」。
  if (diff < 0) {
    const ahead = Math.round(-diff / 60000)
    return ahead < 60 ? `超前 ${ahead} 分钟` : `超前 ${Math.round(ahead / 60)} 小时`
  }
  const mins = Math.round(diff / 60000)
  if (mins < 60) return `${mins} 分钟前`
  const hours = Math.round(mins / 60)
  if (hours < 24) return `${hours} 小时前`
  return `${Math.round(hours / 24)} 天前`
}

/** 生成 ISO8601 带 +08:00 的时间串（后端 Times.parse 认这个格式） */
export function toIsoLocal(date) {
  const d = date instanceof Date ? date : new Date(date)
  const pad = (n) => String(n).padStart(2, '0')
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` +
    `T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}+08:00`
  )
}
