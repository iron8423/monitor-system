<script setup>
import { computed } from 'vue'
import * as echarts from 'echarts'

import EChart from '@/components/EChart.vue'

/**
 * 形变曲线：把「测点时序」这一业务形状翻译成 ECharts 的 option，
 * 渲染交给通用的 {@link EChart}（实例生命周期由它管，这里不碰 DOM）。
 *
 * 拆两层的理由：option 的构造是业务（阈值线、单位、平滑、点密度），
 * 而 init/resize/dispose 是通用管道。混在一起的话，将来加第二张图
 * （比如速率对比、测点横向比较）就得把这段生命周期代码再抄一遍。
 */
const props = defineProps({
  points: { type: Array, default: () => [] }, // [{ t, v }]
  unit: { type: String, default: '' },
  metricLabel: { type: String, default: '' },
  /**
   * 阈值线，画成虚线让「有没有超限」一眼可见。
   * 由调用方从 `/alarm-rules` 换算而来（见 `utils/thresholds.js`），
   * 组件本身不关心规则怎么来的，只负责画。
   */
  thresholds: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  height: { type: String, default: '320px' },
})

const hasData = computed(() => props.points.length > 0)

const option = computed(() => {
  const times = props.points.map((p) => p.t)
  const values = props.points.map((p) => p.v)

  return {
    backgroundColor: 'transparent',
    grid: { left: 56, right: 24, top: 36, bottom: 32 },
    tooltip: {
      trigger: 'axis',
      valueFormatter: (v) => (v === null || v === undefined ? '—' : `${v} ${props.unit}`),
    },
    legend: {
      show: props.thresholds.length > 0,
      right: 16,
      top: 4,
      itemWidth: 14,
      itemHeight: 2,
      textStyle: { fontSize: 11 },
    },
    xAxis: {
      type: 'category',
      data: times,
      axisLabel: { fontSize: 11, hideOverlap: true },
      splitLine: { show: false },
    },
    yAxis: {
      type: 'value',
      name: props.unit,
      nameTextStyle: { fontSize: 11 },
      axisLine: { show: false },
      axisLabel: { fontSize: 11 },
      splitLine: { lineStyle: { color: '#eef1f5' } },
    },
    series: [
      {
        name: props.metricLabel || '测值',
        type: 'line',
        data: values,
        smooth: true,
        // 点太密就只留线，否则一条 24 小时的原始曲线会糊成一片点
        showSymbol: values.length <= 60,
        symbolSize: 5,
        lineStyle: { width: 2 },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(31, 111, 235, 0.22)' },
            { offset: 1, color: 'rgba(31, 111, 235, 0)' },
          ]),
        },
        ...(props.thresholds.length
          ? {
              markLine: {
                silent: true,
                symbol: 'none',
                label: { fontSize: 10, formatter: '{b}' },
                data: props.thresholds.map((t) => ({
                  name: t.label,
                  yAxis: t.value,
                  lineStyle: { color: t.color || '#e6a23c', type: 'dashed', width: 1 },
                })),
              },
            }
          : {}),
      },
    ],
  }
})
</script>

<template>
  <div v-loading="loading" class="chart-wrap" :style="{ height }">
    <!-- 没有数据时不给 EChart 传点，直接把空状态压在图表区上，
         免得空图表的坐标轴看起来像「数据是 0」 -->
    <EChart v-if="hasData" :option="option" height="100%" :loading="loading" />
    <el-empty v-else-if="!loading" description="当前条件下没有数据" :image-size="70" />
  </div>
</template>

<style scoped>
.chart-wrap {
  position: relative;
  width: 100%;
}

.chart-wrap :deep(.el-empty) {
  height: 100%;
}
</style>
