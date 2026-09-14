<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'

import * as api from '@/api/monitor'
import DeviceDrawer from '@/components/DeviceDrawer.vue'
import { DEVICE_STATUS_LABELS, DEVICE_STATUS_TAG, label } from '@/utils/labels'

/**
 * 设备状态（验收第 5 条的操作面：断开模拟器 → 设备标为离线并生成设备告警）。
 *
 * 状态不在这里判定——离线判据在后端 `DeviceStatusPolicy`（5 分钟未上报）。
 * 前端只展示后端给的 `status`，不自己拿 `lastReportTime` 算：
 * 算一遍就等于把判据复制到了两处，两边一旦不同步，界面上「在线」的设备和
 * 后端判定为离线的设备就会不一致，而告警是按后端判定发的。
 *
 * 点行进**详情抽屉**（基本信息 / 绑定测点 / 维护记录），不是跳走。
 * 抽屉是 `DeviceDrawer`，运维台也用同一个——见该组件的注释。
 */

defineOptions({ name: 'DeviceView' })

const rows = ref([])
const loading = ref(false)
let timer = null

/** 抽屉里当前展示的设备；null = 抽屉关着 */
const current = ref(null)

async function load() {
  loading.value = true
  try {
    rows.value = (await api.listDevices()) || []
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  load()
  // 设备状态是后端定时扫描出来的，前端没有推送通道（SSE 只推 measurement/alarm），
  // 所以这里靠轮询。10s 与后端扫描周期同量级。
  timer = setInterval(load, 10000)
})

onBeforeUnmount(() => clearInterval(timer))

function batteryClass(v) {
  if (v == null) return ''
  if (v < 20) return 'mk-danger'
  if (v < 50) return 'mk-warn'
  return ''
}
</script>

<template>
  <div class="device-page">
    <div class="mk-panel table-panel">
      <div class="mk-panel-title">
        设备状态
        <span class="mk-muted title-sub">每 10 秒自动刷新</span>
        <span class="mk-spacer" />
        <el-button size="small" link type="primary" @click="load">刷新</el-button>
      </div>

      <!-- 点行开抽屉（光标改成手型，见 .clickable-row） -->
      <el-table
        :data="rows"
        v-loading="loading"
        size="small"
        class="clickable-row"
        @row-click="current = $event"
      >
        <el-table-column label="设备编号" width="130">
          <template #default="{ row }"><span class="mk-mono">{{ row.code }}</span></template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="160" />
        <el-table-column prop="type" label="类型" width="180" />
        <el-table-column label="序列号" width="150">
          <template #default="{ row }"><span class="mk-mono">{{ row.serialNo || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="DEVICE_STATUS_TAG[row.status] || 'info'" size="small">
              {{ label(DEVICE_STATUS_LABELS, row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="电量" width="100">
          <template #default="{ row }">
            <span class="mk-mono" :class="batteryClass(row.battery)">
              {{ row.battery != null ? `${row.battery}%` : '—' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="最后上报" min-width="180">
          <template #default="{ row }">
            <span class="mk-mono time">{{ row.lastReportTime || '—' }}</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <DeviceDrawer v-model:device="current" />

    <div class="mk-panel">
      <div class="mk-footnote">
        离线判据由后端 <span class="mk-mono">DeviceStatusPolicy</span> 判定（5 分钟未上报），
        前端不重复计算——两边各算一遍迟早会不一致。
        <b>故障</b>（<span class="mk-mono">FAULT</span>）是人工在档案里显式标注的状态，
        优先于推出来的离线，且这类设备<strong>不发离线告警</strong>——它只在设备页与运维台露面。
        低电量目前仅在此处展示，<strong>不产生告警</strong>（已定案暂不做）。
        <b>点任意一行</b>可打开详情抽屉：基本信息、绑定测点、维护记录都在那里。
      </div>
    </div>
  </div>
</template>

<style scoped>
.device-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.table-panel {
  display: flex;
  flex-direction: column;
}

.title-sub {
  margin-left: 10px;
  font-size: 12px;
  font-weight: 400;
}

.time {
  font-size: 12px;
  color: var(--mk-text-sub);
}

/* 表格行可点开抽屉——不改光标的话没人知道能点 */
.clickable-row :deep(.el-table__row) {
  cursor: pointer;
}
</style>
