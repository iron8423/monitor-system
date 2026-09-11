<script setup>
/**
 * KPI 磁贴行：四个角色工作台的第一屏都是它，只是喂进去的指标不同。
 *
 * 从 `HomeView.vue` 里原样提出来的（模板 + 样式），行为不变——原文件已迁为
 * `views/home/HomeAdmin.vue`。抽出来的理由很直接：
 * 本轮一次新增四个页面，不抽就是四份复制粘贴，改一处间距要改四个文件。
 */

defineOptions({ name: 'StatTiles' })

defineProps({
  /**
   * @type {Array<{key: string, label: string, value: number|string|null, unit?: string,
   *               danger?: boolean, ok?: boolean, tip?: string}>}
   * `danger`（>0 时标红）/ `ok`（>0 时标绿）是**语义**不是颜色，由这里统一决定长什么样。
   */
  items: { type: Array, default: () => [] },
})

// 空值给「—」而不是 0：0 和「没数据」是两回事
const fmt = (v) => (v === null || v === undefined ? '—' : v)
</script>

<template>
  <div class="kpi-row">
    <div v-for="k in items" :key="k.key" class="mk-panel kpi">
      <span class="mk-muted kpi-label">
        {{ k.label }}
        <el-tooltip v-if="k.tip" :content="k.tip" placement="top">
          <el-icon class="kpi-tip"><QuestionFilled /></el-icon>
        </el-tooltip>
      </span>
      <span
        class="mk-metric mk-mono"
        :class="{
          'mk-danger': k.danger && k.value > 0,
          'mk-ok': k.ok && k.value > 0,
        }"
      >
        {{ fmt(k.value) }}<small v-if="k.value != null && k.unit"> {{ k.unit }}</small>
      </span>
    </div>
  </div>
</template>

<style scoped>
.kpi-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}

@media (max-width: 900px) {
  .kpi-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

.kpi {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 16px 18px;
}

.kpi-label {
  display: flex;
  gap: 4px;
  align-items: center;
  font-size: 12px;
}

.kpi-tip {
  font-size: 13px;
  cursor: help;
}

.kpi small {
  font-size: 13px;
  font-weight: 400;
}
</style>
