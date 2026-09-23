<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'

import { formatNumber } from '@/utils/format'

/**
 * 会"滚动"的数字（2026-09-23 用户反馈"测点数据太僵硬、点一下什么变化都没有"）。
 *
 * 为什么值得单独做一个组件：大屏是**值班盯屏**的场景，判断"刚才那一下点的是不是新的点"
 * 靠的是数值有没有变化。数字硬切（0.104 → 0.132）在 13 号字上几乎看不出差别，
 * 滚一下（中间插几帧）就能被眼睛捕捉到，用户不用去比对小数第三位。
 *
 * 实现刻意做小：
 *   · 只在 `value` 真的变了时补间，从**旧值**滚到**新值**（不是从 0 开始，那会误导量级）；
 *   · `null`/`NaN` 一律显示 `—`（"没有读数"不能动画成一个数）；
 *   · 尊重系统的"减少动态效果"偏好，开了就直接显示终值。
 */
const props = defineProps({
  value: { type: Number, default: null },
  digits: { type: Number, default: 3 },
  /** 补间时长（毫秒） */
  duration: { type: Number, default: 620 },
})

const prefersReducedMotion = typeof window !== 'undefined'
  && typeof window.matchMedia === 'function'
  && window.matchMedia('(prefers-reduced-motion: reduce)').matches

const shown = ref(Number.isFinite(props.value) ? props.value : null)
let frame = null

function stop() {
  if (frame !== null) {
    cancelAnimationFrame(frame)
    frame = null
  }
}

function tween(from, to) {
  stop()
  if (prefersReducedMotion || !Number.isFinite(from) || from === to) {
    shown.value = to
    return
  }
  const startedAt = performance.now()
  const span = to - from
  const step = () => {
    const t = Math.min(1, (performance.now() - startedAt) / props.duration)
    const eased = 1 - (1 - t) ** 3   // easeOutCubic：起步快、收尾稳，读数字不眼晕
    shown.value = from + span * eased
    if (t < 1) frame = requestAnimationFrame(step)
    else {
      frame = null
      shown.value = to
    }
  }
  frame = requestAnimationFrame(step)
}

watch(
  () => props.value,
  (next, previous) => {
    if (!Number.isFinite(next)) {
      stop()
      shown.value = null
      return
    }
    const from = Number.isFinite(previous) ? previous : (Number.isFinite(shown.value) ? shown.value : next)
    tween(from, next)
  },
)

onBeforeUnmount(stop)
</script>

<template>
  <span class="animated-number">{{ Number.isFinite(shown) ? formatNumber(shown, digits) : '—' }}</span>
</template>

<style scoped>
.animated-number {
  /* 等宽数字：滚动时宽度不跳，整行不会左右晃 */
  font-variant-numeric: tabular-nums;
}
</style>
