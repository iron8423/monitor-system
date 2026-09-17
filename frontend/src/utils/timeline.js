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
 *
 * **前值保持有时限**（清单第 19 条）：设备坏了之后，前值保持会让屏幕上一直挂着
 * 最后一个读数，看上去和「还在正常上报」没有区别。所以超过时限的值要**标出来**
 * （`stale`），但**值本身保留**——过期 ≠ 缺测，清成 null 会丢掉「这个点最后的读数
 * 是 3.2mm」这条信息，值班的人正需要它。时限怎么定见 {@link carryMsOf}。
 */

/** 时间戳统一转毫秒；非法返回 NaN（调用方过滤） */
function ms(t) {
  const v = typeof t === 'number' ? t : Date.parse(t)
  return Number.isFinite(v) ? v : Number.NaN
}

/** 保持时限 = 该点自己的采样节奏 × 这个倍数（容忍连续漏几次上报） */
const CARRY_FACTOR = 3

/**
 * 保持时限的**下限**。出处：后端判设备离线用 `DeviceStatusPolicy.OFFLINE_MINUTES = 5`
 * （`backend/.../asset/DeviceStatusPolicy.java`）。前端多个页面已明确「不重算这个口径、
 * 只引用它」（`DeviceView.vue`、`HomeAdmin.vue` 的注释）——回放的「过期」是同一件事
 * 在时间轴上的说法，所以用同一个数，不另发明一个。
 */
const MIN_CARRY_MS = 5 * 60 * 1000

/**
 * 一条序列的保持时限：**下限与自身节奏取大**。
 *
 * 为什么不能写死一个绝对秒数：本仓自己的两套数据就跨两个数量级——生产基线是 5 秒采样
 * （`tools/production_simulator/README.md`），而演示/验收数据是**1 小时**采样
 * （`tools/acceptance/08-simulator.sh` 断言的相邻采集间隔就是 3600s）。写死 5 分钟的话，
 * 小时级数据在每两次采样之间会有 55 分钟被判成过期，大屏变成一片灰——那正好把本条
 * 要显示的东西变成噪声。
 *
 * 为什么取**中位数**而不是最大间隔：一次长停机（设备坏了 6 小时）会把整条序列的判据
 * 放大到「以后 18 小时都算新鲜」，恰恰把本条要显示的断流藏起来。
 *
 * 单条采样的序列没有间隔可测 → 退回下限。
 */
function carryMsOf(samples) {
  if (samples.length < 2) return MIN_CARRY_MS
  const gaps = []
  for (let i = 1; i < samples.length; i += 1) gaps.push(samples[i].ms - samples[i - 1].ms)
  gaps.sort((a, b) => a - b)
  const median = gaps[Math.floor(gaps.length / 2)]
  return Math.max(CARRY_FACTOR * median, MIN_CARRY_MS)
}

/**
 * 合并多条序列为回放帧。
 *
 * @param {Array<{pointId:number, points:Array<{t:string, v:number|null}>}>} seriesByPoint
 * @param {{maxCarryMs?: number|null}} [options]
 *        `maxCarryMs` 显式给了就所有点都用它（**测试打边界用**，见 `scripts/selfcheck.mjs`）；
 *        不给则按各序列自己的采样节奏自适应（`carryMsOf`）。
 * @returns {Array<{t:string, ms:number,
 *                  values:Record<number, number|null>,
 *                  sampleMs:Record<number, number|null>,
 *                  stale:Record<number, true>}>}
 *          按时间升序。
 *          - `values[pointId]` 该时刻的显示值；该点这一刻之前尚无采样时为 null
 *          - `sampleMs[pointId]` 这个值**来自哪一次采样**。前值保持时它 **≠ frame.ms**，
 *            界面上「采集时间」要显示的就是它——写 frame.ms 等于把「3 小时前的读数」
 *            伪装成「这一时刻的读数」
 *          - `stale` 稀疏表，只记过期的点。空对象 = 全新鲜。
 *            **过期判定只在这里做一次**：让消费方拿 `sampleMs` 自己比阈值，就会出现
 *            `ScreenView` 与 `pointLayer` 各判一次、同一个值两个颜色
 */
export function buildFrames(seriesByPoint, { maxCarryMs = null } = {}) {
  const prepared = (seriesByPoint || [])
    .filter((s) => s && s.pointId != null)
    .map((s) => {
      const samples = (s.points || [])
        .map((p) => ({ ms: ms(p.t), t: p.t, v: p.v ?? null }))
        .filter((p) => Number.isFinite(p.ms))
        .sort((a, b) => a.ms - b.ms)
      return {
        pointId: s.pointId,
        samples,
        carryMs: maxCarryMs != null ? maxCarryMs : carryMsOf(samples),
      }
    })

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
    const sampleMs = {}
    const stale = {}
    for (const s of prepared) {
      let i = cursors.get(s.pointId)
      while (i < s.samples.length && s.samples[i].ms <= t) i += 1
      cursors.set(s.pointId, i)
      if (i === 0) {
        // 这一刻之前该点**还没量到任何值**——是「没有数据」，不是「数据过期」。
        // 两者在界面上必须是不同的态（第 19 条的措辞就是「缺测或过期」）
        values[s.pointId] = null
        sampleMs[s.pointId] = null
        continue
      }
      const sample = s.samples[i - 1]
      values[s.pointId] = sample.v
      sampleMs[s.pointId] = sample.ms
      // 前值保持超时：值留着，但这个点要标出来
      if (t - sample.ms > s.carryMs) stale[s.pointId] = true
    }
    frames.push({ t: timeText.get(t) || new Date(t).toISOString(), ms: t, values, sampleMs, stale })
  }
  return frames
}

/**
 * 一帧里「有数据」的点数（**含过期值**）——用来判断这一帧值不值得停（全空的帧没有信息量）。
 * 语义与第 19 条之前完全一致，不要改。
 */
export function frameDataCount(frame) {
  if (!frame) return 0
  return Object.values(frame.values).filter((v) => v !== null && v !== undefined).length
}

/**
 * 一帧里**没过期**的点数（清单第 19 条）。
 *
 * 界面上「N 个测点有数据」只能数这个：把陈旧值算进「有数据」，正是本条原文说的那个虚高
 * ——设备全断了，屏幕上还写着「7 个测点有数据」。
 */
export function frameFreshCount(frame) {
  if (!frame) return 0
  return Object.entries(frame.values)
    .filter(([id, v]) => v !== null && v !== undefined && frame.stale?.[id] !== true)
    .length
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
