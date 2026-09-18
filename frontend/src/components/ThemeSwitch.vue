<script setup>
import { useTheme } from '@/composables/useTheme'

/**
 * 白天 / 黑夜手动切换。
 *
 * 为什么是一个"分段按钮"而不是一个图标按钮：图标（太阳/月亮）只能表达"点一下会变成什么"，
 * 用户第一次看到时不能确定**当前是哪套**，也容易在密集的顶栏里被忽略。两个带文字的选项 +
 * 高亮当前项，是"我现在在哪、能去哪"都写在脸上的形式。
 *
 * `variant` 只决定取哪一组变量（顶栏 vs 3D 大屏 HUD），不动交互：
 *   · header —— 用 --mk-header-*（工作台顶栏、登录页）
 *   · hud    —— 用 --mk-hud-*（3D 大屏浮层）
 */
defineProps({
  variant: { type: String, default: 'header' },
  size: { type: String, default: 'default' },
})

const { isDark, setTheme } = useTheme()
</script>

<template>
  <div class="theme-switch" :class="[`v-${variant}`, `s-${size}`]" role="group" aria-label="主题切换">
    <button
      type="button"
      class="opt"
      :class="{ active: !isDark }"
      :aria-pressed="!isDark"
      title="切换到白天模式"
      @click="setTheme('light')"
    >
      <el-icon><Sunny /></el-icon><span class="label">白天</span>
    </button>
    <button
      type="button"
      class="opt"
      :class="{ active: isDark }"
      :aria-pressed="isDark"
      title="切换到黑夜模式"
      @click="setTheme('dark')"
    >
      <el-icon><Moon /></el-icon><span class="label">黑夜</span>
    </button>
  </div>
</template>

<style scoped>
.theme-switch {
  display: inline-flex;
  gap: 2px;
  align-items: center;
  padding: 2px;
  border: 1px solid var(--sw-border);
  border-radius: 999px;
}

.v-header {
  --sw-border: var(--mk-header-border);
  --sw-text: var(--mk-header-muted);
  --sw-hover: rgba(31, 111, 235, 0.14);
}

.v-hud {
  --sw-border: var(--mk-hud-border);
  --sw-text: var(--mk-hud-muted);
  --sw-hover: rgba(31, 111, 235, 0.25);
}

.opt {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  padding: 3px 10px;
  font: inherit;
  font-size: 12px;
  line-height: 1.5;
  color: var(--sw-text);
  cursor: pointer;
  background: transparent;
  border: none;
  border-radius: 999px;
  transition: color 0.15s ease, background-color 0.15s ease;
}

.opt:hover {
  color: var(--mk-primary);
  background: var(--sw-hover);
}

/* 当前主题：实心高亮，一眼能看出"现在在哪套" */
.opt.active {
  color: #fff;
  background: var(--mk-primary);
}

.s-small .opt {
  padding: 2px 8px;
  font-size: 11px;
}

.s-small .opt .label {
  display: none;
}
</style>
