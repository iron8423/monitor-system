<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'

import * as api from '@/api/monitor'
import AlarmQueue from '@/components/AlarmQueue.vue'
import StatTiles from '@/components/StatTiles.vue'
import { usePolling } from '@/composables/usePolling'

/**
 * 管理工作台（管理员）。四个角色工作台里唯一保留「全局总览」的一个——
 * 需求 §3 给管理员的定位是建档配阈值、管账号权限与审计，所以要的是全貌而不是待办队列
 * （待办队列在值班/研判/运维三页，见同目录另外三个文件）。
 *
 * 本页只做「当前态」的汇总：KPI 全部来自后端 `GET /projects/{id}/summary`，
 * 前端一个数都不自己算。设备在线数尤其不能自己数——离线判据在后端
 * `DeviceStatusPolicy`（5 分钟未上报），前端再算一遍就会和告警对不上。
 */

defineOptions({ name: 'HomeAdmin' })

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

// 汇总没有推送通道（SSE 只推 measurement/alarm 事件，不推 summary），
// 所以靠轮询兜底。30s 与后端统计口径同量级，不追求实时。
usePolling(load, 30000)

/**
 * 「最大形变」是**带符号**的：后端取各测点最新值中绝对值最大的那个，负向形变不取绝对值
 * （`ProjectSummaryService#maxDeformation`）。所以这里可能是负数，卡片上要解释一句，
 * 否则一个 −1.22mm 摆在「最大」旁边会让人以为算错了。
 */
const kpis = computed(() => [
  { key: 'points', label: '测点总数', value: summary.value?.pointCount, unit: '' },
  // 「测点告警」与「设备告警」合并计数：设备告警只挂 deviceId、不挂测点，
  // 只看队列容易以为漏了（后端 ProjectSummaryService.activeAlarmCount 两条都算）
  { key: 'alerts', label: '未解除警情', value: summary.value?.alertCount, unit: '', danger: true,
    tip: '本项目下所有非终态警情，含设备告警（设备离线这类只挂设备、不挂测点）' },
  { key: 'devices', label: '在线设备', value: summary.value?.onlineDeviceCount, unit: '', ok: true },
  {
    key: 'deform',
    label: '最大形变',
    value: summary.value?.maxDeformationMm,
    unit: 'mm',
    tip: '各测点最新形变中绝对值最大的一个，保留正负号（负向形变同样计入）',
  },
])

/** 管理员专属入口。审计日志后端有接口（仅 ADMIN 可调）但前端还没有页面，故不在此列。 */
const shortcuts = [
  { path: '/admin', title: '管理端', desc: '项目 / 场景 / 对象 / 测点 / 测项 / 设备 / 告警规则的增删改' },
  { path: '/alarms', title: '告警中心', desc: '全部警情、处置时间线；管理员可执行全部六种处置动作' },
  { path: '/screen', title: '3D 大屏', desc: '真实地形 + 卫星影像 + 测点标点的三维态势' },
]
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

    <StatTiles :items="kpis" />

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
        <AlarmQueue
          :alarms="recentAlarms"
          show-status
          empty-text="暂无警情"
          @row-click="router.push('/alarms')"
        />
      </div>
    </div>

    <div class="mk-panel">
      <div class="mk-panel-title">管理入口</div>
      <div class="shortcuts">
        <div
          v-for="s in shortcuts"
          :key="s.path"
          class="shortcut"
          @click="router.push(s.path)"
        >
          <div class="shortcut-title">{{ s.title }}</div>
          <div class="mk-muted shortcut-desc">{{ s.desc }}</div>
        </div>
      </div>
    </div>

    <div class="mk-panel">
      <div class="mk-footnote">
        KPI 全部取自 <span class="mk-mono">GET /projects/{id}/summary</span>，
        「在线设备」按后端
        <span class="mk-mono">DeviceStatusPolicy</span>（5 分钟未上报即离线）判定。
        三维态势见 <span class="mk-mono">/screen</span>（3D 大屏：真实地形 + 卫星影像 + 测点标点）。
        值班 / 研判 / 运维三个岗位各有自己的工作台，登录后按角色自动进入。
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

.shortcuts {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  padding: 14px 16px;
}

@media (max-width: 1100px) {
  .shortcuts {
    grid-template-columns: minmax(0, 1fr);
  }
}

.shortcut {
  padding: 12px 14px;
  border: 1px solid var(--mk-border);
  border-radius: 6px;
  cursor: pointer;
  transition: border-color 0.15s;
}

.shortcut:hover {
  border-color: var(--mk-primary);
}

.shortcut-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--mk-primary);
}

.shortcut-desc {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.6;
}
</style>
