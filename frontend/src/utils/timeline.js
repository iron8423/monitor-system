/**
 * 时间轴回放的数据整形（纯函数，可直接跑断言）。
 *
 * 为什么要「帧」这一层：`/points/{id}/series` 是**按测点各拉一份**的，各点的采样时刻
 * 并不对齐（设备补传、点号不同批上报都会错开）。回放要的是「某一时刻，**所有点**分别
 * 是多少」，所以必须先把 N 条序列合并到同一条时间轴上——各点用**前值保持**（carry-forward）：
 * 该点在这一刻之前最近的一次采样值，就是它在屏幕上的值。
 *
 * 这不只是显示口径问题：用「最近的采样」而不是「最近的未来采样」才符合监测的直觉
 * —— 屏幕上任何时刻看到的值，都必须是**那时已经量到**的值（不能用未来的数据倒推）。
 */

/** 时间戳统一转毫秒；非法返回 NaN（调用方过滤） */
function ms(t) {
  const v = typeof t === 'number' ? t : Date.parse(t)
  return Number.isFinite(v) ? v : Number.NaN
}

/**
 * 合并多条序列为回放帧。
 *
 * @param {Array<{pointId:number, points:Array<{t:string, v:number|null}>}>} seriesByPoint
 * @returns {Array<{t:string, ms:number, values:Record<number, number|null>}>}
 *          按时间升序；`values[pointId]` 为该时刻的显示值（该点尚无数据时为 null）
 */
export function buildFrames(seriesByPoint) {
  const prepared = (seriesByPoint || [])
    .filter((s) => s && s.pointId != null)
    .map((s) => ({
      pointId: s.pointId,
      samples: (s.points || [])
        .map((p) => ({ ms: ms(p.t), t: p.t, v: p.v ?? null }))
        .filter((p) => Number.isFinite(p.ms))
        .sort((a, b) => a.ms - b.ms),
    }))

  const times = [...new Set(prepared.flatMap((s) => s.samples.map((x) => x.ms)))].sort((a, b) => a - b)

  // 展示用的时间串沿用后端给的原文（带 +08:00），不要自己转成 UTC 再让界面显示差 8 小时
  const timeText = new Map()
  for (const s of prepared) {
    for (const sample of s.samples) {
      if (!timeText.has(sample.ms)) timeText.set(sample.ms, sample.t)
    }
  }

  // 每条序列一个游标：随帧前进只增不减，整体是 O(总采样数)
  const cursors = new Map(prepared.map((s) => [s.pointId, 0]))
  const frames = []
  for (const t of times) {
    const values = {}
    for (const s of prepared) {
      let i = cursors.get(s.pointId)
      while (i < s.samples.length && s.samples[i].ms <= t) i += 1
      cursors.set(s.pointId, i)
      values[s.pointId] = i > 0 ? s.samples[i - 1].v : null
    }
    frames.push({ t: timeText.get(t) || new Date(t).toISOString(), ms: t, values })
  }
  return frames
}

/**
 * 一帧里「有数据」的点数——用来判断这一帧值不值得停（全空的帧没有信息量）。
 */
export function frameDataCount(frame) {
  if (!frame) return 0
  return Object.values(frame.values).filter((v) => v !== null && v !== undefined).length
}

/**
 * 回放进度归一化（0~1000，避免滑块抖动；el-slider 用整数步长最省心）。
 * 单帧或空帧返回 0。
 */
export function frameProgress(index, total) {
  if (!total || total <= 1) return 0
  return Math.round((index / (total - 1)) * 1000)
}

export function indexFromProgress(progress, total) {
  if (!total || total <= 1) return 0
  return Math.min(total - 1, Math.max(0, Math.round((progress / 1000) * (total - 1))))
}
