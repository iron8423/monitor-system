<script setup>
import * as echarts from 'echarts'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

defineOptions({ name: 'EChart' })

const props = defineProps({
  option: { type: Object, required: true },
  height: { type: String, default: '320px' },
  loading: { type: Boolean, default: false },
})

const el = ref(null)
let chart = null
let resizeObserver = null

onMounted(() => {
  chart = echarts.init(el.value)
  chart.setOption(props.option)
  if (props.loading) chart.showLoading()

  // 容器尺寸变化时重绘（侧边栏折叠、窗口缩放都靠它）
  resizeObserver = new ResizeObserver(() => chart?.resize())
  resizeObserver.observe(el.value)
})

watch(
  () => props.option,
  (option) => chart?.setOption(option, { notMerge: true }),
  { deep: true },
)

watch(
  () => props.loading,
  (loading) => (loading ? chart?.showLoading() : chart?.hideLoading()),
)

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  chart?.dispose()
  chart = null
})

defineExpose({
  getInstance: () => chart,
  resize: () => chart?.resize(),
})
</script>

<template>
  <div ref="el" class="echart" :style="{ height }" />
</template>

<style scoped>
.echart {
  width: 100%;
}
</style>
