<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'

import * as api from '@/api/monitor'
import AlarmQueue from '@/components/AlarmQueue.vue'
import StatTiles from '@/components/StatTiles.vue'
import { usePolling } from '@/composables/usePolling'
import { toIsoLocal } from '@/utils/format'

/**
 * 值班工作台（值班员）。需求 §3 给值班员的定位是「盯实时、收到告警、点确认」，
 * 所以这一页就是一个待确认队列 + 几个态势数。
 *
 * **为什么队列是 `status=PENDING` 而不是「分给我的」**：后端没有
 * `Alarm.assignee` 这类字段，也没有任何「按当前用户过滤」的读端点，做不出「我的警情」。
 * 好在动作→目标状态的映射是确定的（`AlarmConstants`：confirm→CONFIRMED、
 * research→OBSERVING、handle/dispatch→PROCESSING），所以**岗位的待办就是状态的一段**：
 * 值班员收 PENDING、研判员收 CONFIRMED、运维员收 PROCESSING。
 * 这是本轮三个角色页能各不相同的原因，也是唯一站得住的划分。
 */

defineOptions({ name: 'HomeOperator' })

const router = useRouter()

const summary = ref(null)
const queue = ref([])
const pendingTotal = ref(null)
const lastHourTotal = ref(null)
const loading = ref(true)
const errorMsg = ref('')

async function load() {
  loading.value = true
  errorMsg.value = ''
  try {
    const projects = await api.listProjects().catch(() => [])
    const pid = projects?.[0]?.id

    // 近 1 小时的起点。走 toIsoLocal 生成带 +08:00 的串——后端 `Times.parse` 认这个
    // 格式，而 `new Date().toISOString()` 给的是 UTC 的 Z，两者不是一回事。
    const from = toIsoLocal(new Date(Date.now() - 60 * 60 * 1000))

    const [sum, pending, recent] = await Promise.all([
      pid ? api.projectSummary(pid).catch(() => null) : null,
      api.listAlarms({ status: 'PENDING', pageNum: 1, pageSize: 8 }).catch(() => null),
      api.listAlarms({ from, pageNum: 1, pageSize: 1 }).catch(() => null),
    ])
    summary.value = sum
    queue.value = pending?.records || []
    // total 是**满足条件的总数**，与 pageSize 无关；下面的表只显示前 8 条，卡片给全量
    pendingTotal.value = pending?.total ?? null
    lastHourTotal.value = recent?.total ?? null
  } catch (e) {
    errorMsg.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

// 「盯实时」：设备离线扫描是 10s 一轮，这里跟它同量级。
// （布局上那条全局 SSE 只用于顶栏状态灯，页面拿不到——见 frontend/README 已知限制。）
usePolling(load, 10000)

const kpis = computed(() => [
  {
    key: 'pending',
    label: '待确认警情',
    value: pendingTotal.value,
    unit: '',
    danger: true,
    tip: '状态为 PENDING 的警情。值班员可执行：确认 / 派单 / 处置 / 解除 / 误报',
  },
  // 「测点告警」与「设备告警」合并计数：设备告警只挂 deviceId、不挂测点，
  // 只看队列容易以为漏了（后端 ProjectSummaryService.activeAlarmCount 两条都算）
  { key: 'alerts', label: '未解除警情', value: summary.value?.alertCount, unit: '', danger: true,
    tip: '本项目下所有非终态警情，含设备告警（设备离线这类只挂设备、不挂测点）' },
  { key: 'devices', label: '在线设备', value: summary.value?.onlineDeviceCount, unit: '', ok: true },
  { key: 'hour', label: '近 1 小时新增', value: lastHourTotal.value, unit: '' },
])

function openAlarm(row) {
  router.push({ path: '/alarms', query: { id: row.id } })
}
</script>

<template>
  <div class="page" v-loading="loading">
    <el-alert
      v-if="errorMsg"
      type="error"
      :closable="false"
      show-icon
      :title="errorMsg"
      class="mb"
    />

    <StatTiles :items="kpis" />

    <div class="mk-panel">
      <div class="mk-panel-title">
        待确认警情
        <span class="mk-muted title-sub">每 10 秒自动刷新</span>
        <span class="mk-spacer" />
        <el-button
          size="small"
          link
          type="primary"
          @click="router.push({ path: '/alarms', query: { status: 'PENDING' } })"
        >
          去告警中心处理
        </el-button>
      </div>
      <AlarmQueue
        :alarms="queue"
        :loading="loading"
        empty-text="没有待确认的警情"
        @row-click="openAlarm"
      />
    </div>

    <div class="mk-panel">
      <div class="mk-footnote">
        本页是<b>岗位待办</b>，不是「分给我的警情」——后端没有负责人字段，也没有按当前用户
        过滤的接口，所以队列按状态取：值班员收
        <span class="mk-mono">PENDING</span>（待确认）。点任一行直接开那条警情的详情。
        「在线设备」的离线判据在后端 <span class="mk-mono">DeviceStatusPolicy</span>
        （5 分钟未上报即离线）。
      </div>
    </div>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.mb {
  margin-bottom: 0;
}

.title-sub {
  margin-left: 10px;
  font-size: 12px;
  font-weight: 400;
}
</style>
