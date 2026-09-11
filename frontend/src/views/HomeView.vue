<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import * as api from '@/api/monitor'
import {
  ALARM_TYPE_LABELS, LEVEL_LABELS, LEVEL_TAG, STATUS_LABELS, STATUS_TAG, label,
} from '@/utils/labels'

/**
 * 总览（阶段 2）。
 *
 * 本页只做「当前态」的汇总：KPI 全部来自后端 `GET /projects/{id}/summary`，
 * 前端一个数都不自己算。设备在线数尤其不能自己数——离线判据在后端
 * `DeviceStatusPolicy`（5 分钟未上报），前端再算一遍就会和告警对不上。
 *
 * 3D 大屏是独立的 `/screen`（阶段 3），不在这里放半张地图充数。
 */

defineOptions({ name: 'HomeView' })

const router = useRouter()

const summary = ref(null)
const projects = ref([])
const points = ref([])
const recentAlarms = ref([])
const loading = ref(true)
const errorMsg = ref('')

const projectName = computed(() => projects.value[0]?.name || '—')

async function load() {
  loading.value = true
  errorMsg.value = ''
  try {
    // 项目 id 从 /projects 取，不写死——写死 1 在换库/换种子后会静默指向别的项目
    const [prjs, pts] = await Promise.all([api.listProjects(), api.listPoints()])
    projects.value = prjs || []
    points.value = pts || []

    const pid = projects.value[0]?.id
    const [sum, alarms] = await Promise.all([
      pid ? api.projectSummary(pid).catch(() => null) : null,
      api.listAlarms({ pageNum: 1, pageSize: 5 }).catch(() => null),
    ])
    summary.value = sum
    recentAlarms.value = alarms?.records || []
  } catch (e) {
    errorMsg.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

let timer = null

onMounted(() => {
  load()
  // 汇总没有推送通道（SSE 只推 measurement/alarm 事件，不推 summary），
  // 所以靠轮询兜底。30s 与后端统计口径同量级，不追求实时。
  timer = setInterval(load, 30000)
})

onBeforeUnmount(() => clearInterval(timer))

/**
 * 「最大形变」是**带符号**的：后端取各测点最新值中绝对值最大的那个，负向形变不取绝对值
 * （`ProjectSummaryService#maxDeformation`）。所以这里可能是负数，卡片上要解释一句，
 * 否则一个 −1.22mm 摆在「最大」旁边会让人以为算错了。
 */
const kpis = computed(() => [
  { key: 'points', label: '测点总数', value: summary.value?.pointCount, unit: '' },
  { key: 'alerts', label: '未解除警情', value: summary.value?.alertCount, unit: '', danger: true },
  { key: 'devices', label: '在线设备', value: summary.value?.onlineDeviceCount, unit: '', ok: true },
  {
    key: 'deform',
    label: '最大形变',
    value: summary.value?.maxDeformationMm,
    unit: 'mm',
    tip: '各测点最新形变中绝对值最大的一个，保留正负号（负向形变同样计入）',
  },
])

const fmt = (v) => (v === null || v === undefined ? '—' : v)
</script>

<template>
  <div class="home" v-loading="loading">
    <el-alert
      v-if="errorMsg"
      type="error"
      :closable="false"
      show-icon
      :title="errorMsg"
      class="mb"
    />

    <div class="kpi-row">
      <div v-for="k in kpis" :key="k.key" class="mk-panel kpi">
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

    <div class="cols">
      <div class="mk-panel col">
        <div class="mk-panel-title">
          测点
          <span class="mk-muted title-sub">{{ projectName }}</span>
          <span class="mk-spacer" />
          <el-button size="small" link type="primary" @click="router.push('/points')">
            看曲线
          </el-button>
        </div>
        <div class="chips">
          <el-tag v-for="p in points" :key="p.id" size="small" effect="plain">
            <span class="mk-mono">{{ p.code }}</span>
            <span class="chip-name mk-muted">{{ p.name }}</span>
          </el-tag>
          <el-empty v-if="!points.length" description="没有测点" :image-size="50" />
        </div>
      </div>

      <div class="mk-panel col">
        <div class="mk-panel-title">
          最近警情
          <span class="mk-spacer" />
          <el-button size="small" link type="primary" @click="router.push('/alarms')">
            全部
          </el-button>
        </div>
        <el-table :data="recentAlarms" size="small" :show-header="false">
          <el-table-column width="70">
            <template #default="{ row }">
              <el-tag :type="LEVEL_TAG[row.level]" size="small" effect="dark">
                {{ label(LEVEL_LABELS, row.level) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column width="60">
            <template #default="{ row }">
              <span class="mk-muted">{{ label(ALARM_TYPE_LABELS, row.alarmType) }}</span>
            </template>
          </el-table-column>
          <el-table-column min-width="120">
            <template #default="{ row }">
              <span class="mk-mono">{{ row.alarmType === 'DEVICE' ? row.deviceCode : row.pointCode }}</span>
            </template>
          </el-table-column>
          <el-table-column width="90">
            <template #default="{ row }">
              <el-tag :type="STATUS_TAG[row.status]" size="small">
                {{ label(STATUS_LABELS, row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column min-width="170">
            <template #default="{ row }">
              <span class="mk-mono mk-muted time">{{ row.triggeredAt }}</span>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="暂无警情" :image-size="50" />
          </template>
        </el-table>
      </div>
    </div>

    <div class="mk-panel">
      <div class="mk-footnote">
        KPI 全部取自 <span class="mk-mono">GET /projects/{id}/summary</span>，
        「在线设备」按后端
        <span class="mk-mono">DeviceStatusPolicy</span>（5 分钟未上报即离线）判定。
        三维态势见 <span class="mk-mono">/screen</span>（3D 大屏：真实地形 + 卫星影像 + 测点标点）。
      </div>
    </div>
  </div>
</template>

<style scoped>
.home {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.mb {
  margin-bottom: 0;
}

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

.cols {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.4fr);
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

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 14px 16px;
}

.chip-name {
  margin-left: 6px;
}

.time {
  font-size: 12px;
}
</style>
