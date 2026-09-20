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
  /**
   * 窗口内的**测量基准变更**（P1-4）：`[{ effectiveFrom, reasonLabel, note }]`。
   * 画成竖虚线——换基准之后累计形变从 0 重来，图上那一段陡降/陡升必须能被读成
   * 「换过基准」而不是「稳定了」。
   */
  baselines: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  height: { type: String, default: '320px' },
})

const hasData = computed(() => props.points.length > 0)

/**
 * y 轴范围**必须把阈值线算进去**，否则阈值线根本看不见。
 *
 * 这是实测撞出来的：演示库 7 个测点的 `defo_mm` 都在 0.09–0.14mm 之间，而阈值在 ±3 / +5。
 * ECharts 的 markLine 坐标走轴空间、并**被绘图区裁掉**，轴范围又只按数据算——
 * 于是阈值线全部落在视口外，一根都画不出来。换句话说，这块功能此前是**视觉上的死代码**：
 * 代码里写着 ±3mm（写死的版本连标签都是错的），屏幕上却什么都没有，所以谁也没发现。
 *
 * 代价要说清楚：把 ±3/+5 纳入范围后，0.1 量级的数据会被压成贴着零的一条平线。
 * 但这是**如实**的——它正确表达了「当前形变远低于阈值」。反过来只按数据缩放的话，
 * 这条线看着有起伏，却看不出离阈值还有多远，阈值线也就失去了意义。
 * 形变监测里「离报警线还有多少」正是要看的东西，故选前者。
 */
const yExtent = computed(() => {
  const values = props.points.map((p) => p.v).filter((v) => typeof v === 'number' && Number.isFinite(v))
  const all = [...values, ...props.thresholds.map((t) => Number(t.value)).filter(Number.isFinite)]
  if (!all.length) return {}
  let min = Math.min(...all)
  let max = Math.max(...all)
  // 全平的序列（或只有一个点）会给 min===max，留点余量免得轴塌成一条线
  if (min === max) {
    min -= 1
    max += 1
  }
  const pad = (max - min) * 0.08
  return { min: min - pad, max: max + pad }
})

const option = computed(() => {
  const times = props.points.map((p) => p.t)
  const values = props.points.map((p) => p.v)

  /*
   * 基准标记画在「新基准生效后的第一个数据点」上：
   * markLine 在类目轴上要精确落在某个类目（时间字符串）上，而基准的生效时刻很少
   * 恰好等于某个采样点——所以取第一个不早于它的点，用**索引**定位（比字符串匹配稳，
   * 分桶粒度下也不会因为"桶起点"与生效时刻对不上而整条线消失）。
   */
  const baselineMarks = props.baselines.map((b) => {
    const at = Date.parse(b.effectiveFrom)
    let idx = times.findIndex((t) => Date.parse(t) >= at)
    if (idx < 0) idx = times.length - 1   // 基准晚于最后一个点（理论上窗口内不会）→ 标在末端
    return {
      name: b.reasonLabel || b.reason || '基准变更',
      xAxis: idx,
      lineStyle: { color: '#a065e8', type: 'dashed', width: 1.4 },
    }
  })
  const thresholdMarks = props.thresholds.map((t) => ({
    name: t.label,
    yAxis: t.value,
    lineStyle: { color: t.color || '#e6a23c', type: 'dashed', width: 1 },
  }))
  const marks = [...thresholdMarks, ...baselineMarks]

  return {
    backgroundColor: 'transparent',
    grid: { left: 56, right: 24, top: 36, bottom: 32 },
    tooltip: {
      trigger: 'axis',
      valueFormatter: (v) => (v === null || v === undefined ? '—' : `${v} ${props.unit}`),
    },
    legend: {
      // 阈值线与基准标记都会在图例里出现，任一种存在就显示图例
      show: marks.length > 0,
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
      // 纳入阈值后的范围；空对象时交给 ECharts 自己按数据缩放
      ...yExtent.value,
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
        ...(marks.length
          ? {
              markLine: {
                silent: true,
                symbol: 'none',
                label: { fontSize: 10, formatter: '{b}' },
                data: marks,
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
