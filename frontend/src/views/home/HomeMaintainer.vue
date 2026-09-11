<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'

import * as api from '@/api/monitor'
import AlarmQueue from '@/components/AlarmQueue.vue'
import StatTiles from '@/components/StatTiles.vue'
import { usePolling } from '@/composables/usePolling'
import { DEVICE_STATUS_LABELS, DEVICE_STATUS_TAG, label } from '@/utils/labels'
import { fromNow } from '@/utils/format'

/**
 * 运维工作台（运维员）。需求 §3 给运维员的定位是「处理设备掉线/没电/数据异常」——
 * 所以这一页的主区块是**设备异常表**，警情队列反而是次要的（运维员也能 handle/resolve）。
 *
 * 队列取 `status=PROCESSING`：dispatch 与 handle 都落到 PROCESSING，那正是等运维执行的状态。
 *
 * 口径两处，都别自己发明：
 *   - **在离线**用后端 `GET /devices` 返回的 `status`。该端点被 DeviceController 覆盖过，
 *     返回的是按 `DeviceStatusPolicy` 现算的值而不是档案表里的存量值（那里有详尽注释）。
 *     前端不拿 `lastReportTime` 自己算——算一遍就把判据复制成了两份，而告警是按后端判据发的。
 *   - **低电**沿用 20% 这条线，与 `DeviceView.batteryClass`、后端
 *     `DeviceStatusPolicy.LOW_BATTERY_THRESHOLD` 同值。这是前端唯一一处复制了后端阈值的地方
 *     （沿袭 DeviceView 的既有做法，不是本轮新引入的），已在脚注与 README 里记明。
 */

defineOptions({ name: 'HomeMaintainer' })

const router = useRouter()

/** 与后端 DeviceStatusPolicy.LOW_BATTERY_THRESHOLD、DeviceView.batteryClass 同一根线 */
const LOW_BATTERY_PERCENT = 20

const devices = ref([])
const queue = ref([])
const processingTotal = ref(null)
const loading = ref(true)
const errorMsg = ref('')

const isLowBattery = (d) => d.battery != null && d.battery < LOW_BATTERY_PERCENT

/**
 * 故障也进异常表。`FAULT` 不是离线的另一种说法——它是人工在档案里标注的硬件故障，
 * 在 `DeviceStatusPolicy.statusOf` 里优先于离线，而且 `DeviceAlarmMonitor.isOffline`
 * 对 FAULT 直接返回 false，**所以这类设备不发离线告警**：告警流里看不见它，
 * 这一页是它唯一会露面的地方。漏了它，运维员就永远不知道有台设备被标了故障。
 */
const isAbnormal = (d) => d.status === 'FAULT' || d.status === 'OFFLINE' || isLowBattery(d)

/** 排前头的优先级：故障（哑的，最容易忘）→ 离线（新发生）→ 低电（还能撑一阵） */
const RANK = { FAULT: 0, OFFLINE: 1 }

/** 按严重度排，同级内电量低的在前——最该先看的排最上面 */
const anomalies = computed(() =>
  devices.value
    .filter(isAbnormal)
    .sort((a, b) => {
      const ra = RANK[a.status] ?? 2
      const rb = RANK[b.status] ?? 2
      if (ra !== rb) return ra - rb
      return (a.battery ?? 100) - (b.battery ?? 100)
    }),
)

const offlineCount = computed(() => devices.value.filter((d) => d.status === 'OFFLINE').length)
const faultCount = computed(() => devices.value.filter((d) => d.status === 'FAULT').length)
const lowBatteryCount = computed(() => devices.value.filter(isLowBattery).length)
const onlineCount = computed(() => devices.value.filter((d) => d.status === 'ONLINE').length)

async function load() {
  loading.value = true
  errorMsg.value = ''
  try {
    const [devs, processing] = await Promise.all([
      api.listDevices().catch(() => []),
      api.listAlarms({ status: 'PROCESSING', pageNum: 1, pageSize: 8 }).catch(() => null),
    ])
    devices.value = devs || []
    queue.value = processing?.records || []
    processingTotal.value = processing?.total ?? null
  } catch (e) {
    errorMsg.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

// 设备离线是后端定时扫描出来的（`monitor.device-offline.sweep-ms`，默认 10s），
// 这里与它同量级。比后端更快没有意义，只会白刷接口。
usePolling(load, 10000)

/**
 * 四格都取「异常」而不是「在线」：这一岗要的是待办，不是态势。
 * 「在线」放进下面表头的 `在线 x / 共 y` 里——它是个分母，不该占一格。
 * 三格设备数互斥且守恒（在线 + 离线 + 故障 = 总数，见 labels.js），
 * 低电是**另算**的维度，一台设备可能既离线又低电，别把它们相加。
 */
const kpis = computed(() => [
  {
    key: 'fault',
    label: '故障设备',
    value: faultCount.value,
    unit: '',
    danger: true,
    tip: '档案中被人工标注为 FAULT 的设备。这类设备不发离线告警，只在这一页能看到',
  },
  {
    key: 'offline',
    label: '离线设备',
    value: offlineCount.value,
    unit: '',
    danger: true,
    tip: '超过 5 分钟未上报即判定离线（后端 DeviceStatusPolicy.OFFLINE_MINUTES）',
  },
  {
    key: 'battery',
    label: '低电设备',
    value: lowBatteryCount.value,
    unit: '',
    danger: true,
    tip: '电量低于 20%。低电目前只展示、不产生告警，且与上两格可能重叠',
  },
  { key: 'processing', label: '待处置警情', value: processingTotal.value, unit: '' },
])

function batteryClass(v) {
  if (v == null) return ''
  if (v < LOW_BATTERY_PERCENT) return 'mk-danger'
  if (v < 50) return 'mk-warn'
  return ''
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

    <div class="cols">
      <div class="mk-panel col">
        <div class="mk-panel-title">
          设备异常
          <span class="mk-muted title-sub">
            {{ anomalies.length }} 台 · 在线 {{ onlineCount }} / 共 {{ devices.length }}
          </span>
          <span class="mk-spacer" />
          <el-button size="small" link type="primary" @click="router.push('/devices')">
            全部设备
          </el-button>
        </div>
        <el-table
          :data="anomalies"
          size="small"
          class="clickable"
          @row-click="router.push('/devices')"
        >
          <el-table-column label="编号" min-width="130">
            <template #default="{ row }">
              <span class="mk-mono">{{ row.code }}</span>
            </template>
          </el-table-column>
          <el-table-column label="名称" min-width="120">
            <template #default="{ row }">{{ row.name || '—' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag :type="DEVICE_STATUS_TAG[row.status] || 'info'" size="small">
                {{ label(DEVICE_STATUS_LABELS, row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="电量" width="80">
            <template #default="{ row }">
              <span class="mk-mono" :class="batteryClass(row.battery)">
                {{ row.battery != null ? `${row.battery}%` : '—' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="最近上报" min-width="110">
            <template #default="{ row }">
              <span class="mk-muted">{{ fromNow(row.lastReportTime) }}</span>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="设备全部正常" :image-size="50" />
          </template>
        </el-table>
      </div>

      <div class="mk-panel col">
        <div class="mk-panel-title">
          待处置警情
          <span class="mk-spacer" />
          <el-button
            size="small"
            link
            type="primary"
            @click="router.push({ path: '/alarms', query: { status: 'PROCESSING' } })"
          >
            去告警中心
          </el-button>
        </div>
        <AlarmQueue
          :alarms="queue"
          :loading="loading"
          empty-text="没有待处置的警情"
          @row-click="(row) => router.push({ path: '/alarms', query: { id: row.id } })"
        />
      </div>
    </div>

    <div class="mk-panel">
      <div class="mk-footnote">
        设备状态（在线 / 离线 / 故障）全部取自后端 <span class="mk-mono">GET /devices</span>
        现算的结果，前端一个都不自己推——判据在后端
        <span class="mk-mono">DeviceStatusPolicy</span>（5 分钟未上报即离线、FAULT 优先），
        本页只排序不过滤口径。<b>故障设备不发离线告警</b>（<span class="mk-mono">DeviceAlarmMonitor</span>
        对 FAULT 直接放行），所以它不会出现在下面的队列里，只有上面的表能看到。
        低电阈值 {{ LOW_BATTERY_PERCENT }}% 是本页唯一一处复制了后端阈值的地方（与
        <span class="mk-mono">/devices</span> 页同口径），后端一旦调整
        <span class="mk-mono">LOW_BATTERY_THRESHOLD</span> 这里要跟着改；
        低电也是唯一不产生告警的异常，<b>只展示</b>（已定案暂不做）。
        队列按状态取：运维员收 <span class="mk-mono">PROCESSING</span>（派单与处置都落到这个状态），
        可执行 <span class="mk-mono">handle</span> / <span class="mk-mono">resolve</span> /
        <span class="mk-mono">misreport</span>。设备维护记录后端已有接口
        （<span class="mk-mono">/api/v1/maintenance-records</span>），前端尚未做页面。
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

.cols {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr);
  gap: 16px;
}

@media (max-width: 1100px) {
  .cols {
    grid-template-columns: minmax(0, 1fr);
  }
}

.col {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.title-sub {
  margin-left: 10px;
  font-size: 12px;
  font-weight: 400;
}

.clickable :deep(.el-table__row) {
  cursor: pointer;
}
</style>
