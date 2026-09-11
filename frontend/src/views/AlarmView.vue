<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import * as api from '@/api/monitor'
import { useUserStore } from '@/stores/user'
import {
  ACTION_LABELS, ACTIONS_BY_ROLE, ALARM_TYPE_LABELS, LEVEL_LABELS, LEVEL_TAG,
  STATUS_LABELS, STATUS_TAG, TERMINAL_STATUSES, label,
} from '@/utils/labels'

/**
 * 告警中心（阶段 4 / 验收第 3、4 条的操作面）。
 * 验收第 4 条要的是「确认 → 研判 → 处置 → 解除，全过程时间线可追溯」，
 * 所以详情里的时间线是本页的主内容，处置按钮只是入口。
 */

defineOptions({ name: 'AlarmView' })

const userStore = useUserStore()

const rows = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(20)
const loading = ref(false)

const filters = ref({ level: '', status: '', alarmType: '' })

const detail = ref(null)
const detailLoading = ref(false)
const drawerVisible = ref(false)
const actionComment = ref('')

const isTerminal = (status) => TERMINAL_STATUSES.includes(status)

const availableActions = computed(() => {
  if (!detail.value || isTerminal(detail.value.status)) return []
  // 认不出来的角色给空集：这里曾写成 `|| ACTIONS_BY_ROLE.ADMIN`，那是**开放回退**——
  // 角色字段一旦丢了或对不上，界面上反而把管理员的整套动作亮出来。既然后端按角色强制
  // 放行，前端就该同向失败（藏起来），而不是反向放宽。
  return ACTIONS_BY_ROLE[userStore.role] || []
})

async function load() {
  loading.value = true
  try {
    const data = await api.listAlarms({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      ...Object.fromEntries(Object.entries(filters.value).filter(([, v]) => v)),
    })
    rows.value = data?.records || []
    total.value = data?.total || 0
  } finally {
    loading.value = false
  }
}

async function openDetail(row) {
  drawerVisible.value = true
  detailLoading.value = true
  actionComment.value = ''
  try {
    detail.value = await api.alarmDetail(row.id)
  } catch {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

async function doAction(action) {
  const actionText = label(ACTION_LABELS, action)
  if (action === 'misreport' || action === 'resolve') {
    const ok = await ElMessageBox.confirm(
      `确认执行「${actionText}」？该动作会把警情置为终态，之后不再受理处置。`,
      '确认',
      { type: 'warning' },
    ).catch(() => false)
    if (!ok) return
  }
  try {
    detail.value = await api.alarmAction(detail.value.id, action, actionComment.value || undefined)
    actionComment.value = ''
    ElMessage.success(`已${actionText}`)
    load()
  } catch {
    // 拦截器已经弹过错误；这里只需让列表保持原样
  }
}

onMounted(load)
watch([pageNum, pageSize], load)

function onFilterChange() {
  pageNum.value = 1
  load()
}
</script>

<template>
  <div class="alarm-page">
    <div class="mk-panel table-panel">
      <div class="mk-panel-title">
        告警中心
        <span class="mk-muted title-sub">共 {{ total }} 条</span>
      </div>

      <div class="toolbar">
        <el-select v-model="filters.level" placeholder="等级" clearable size="small" style="width: 110px" @change="onFilterChange">
          <el-option v-for="(v, k) in LEVEL_LABELS" :key="k" :label="v" :value="k" />
        </el-select>
        <el-select v-model="filters.status" placeholder="状态" clearable size="small" style="width: 120px" @change="onFilterChange">
          <el-option v-for="(v, k) in STATUS_LABELS" :key="k" :label="v" :value="k" />
        </el-select>
        <el-select v-model="filters.alarmType" placeholder="类型" clearable size="small" style="width: 110px" @change="onFilterChange">
          <el-option v-for="(v, k) in ALARM_TYPE_LABELS" :key="k" :label="v" :value="k" />
        </el-select>
        <span class="mk-spacer" />
        <el-button size="small" link type="primary" @click="load">刷新</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" size="small" @row-click="openDetail">
        <el-table-column label="等级" width="80">
          <template #default="{ row }">
            <el-tag :type="LEVEL_TAG[row.level]" size="small" effect="dark">
              {{ label(LEVEL_LABELS, row.level) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="70">
          <template #default="{ row }">{{ label(ALARM_TYPE_LABELS, row.alarmType) }}</template>
        </el-table-column>
        <el-table-column label="对象" min-width="130">
          <template #default="{ row }">
            <span class="mk-mono">{{ row.alarmType === 'DEVICE' ? row.deviceCode : row.pointCode }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="STATUS_TAG[row.status]" size="small">
              {{ label(STATUS_LABELS, row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="触发时间" min-width="180">
          <template #default="{ row }">
            <span class="mk-mono time">{{ row.triggeredAt }}</span>
          </template>
        </el-table-column>
        <el-table-column label="最近动作" width="100">
          <template #default="{ row }">{{ label(ACTION_LABELS, row.lastAction) }}</template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="pageNum"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          size="small"
          background
        />
      </div>
    </div>

    <el-drawer v-model="drawerVisible" size="520px" :title="detail ? `警情 #${detail.id}` : '警情详情'">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="等级">
              <el-tag :type="LEVEL_TAG[detail.level]" size="small" effect="dark">
                {{ label(LEVEL_LABELS, detail.level) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="STATUS_TAG[detail.status]" size="small">
                {{ label(STATUS_LABELS, detail.status) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="类型">{{ label(ALARM_TYPE_LABELS, detail.alarmType) }}</el-descriptions-item>
            <el-descriptions-item label="对象">
              <span class="mk-mono">{{ detail.alarmType === 'DEVICE' ? detail.deviceCode : detail.pointCode }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="触发时间" :span="2">
              <span class="mk-mono">{{ detail.triggeredAt }}</span>
            </el-descriptions-item>
            <el-descriptions-item v-if="detail.resolvedAt" label="解除时间" :span="2">
              <span class="mk-mono">{{ detail.resolvedAt }}</span>
            </el-descriptions-item>
          </el-descriptions>

          <h4 class="section">触发快照</h4>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item v-for="(v, k) in detail.snapshot" :key="k" :label="k">
              <span class="mk-mono">{{ v }}</span>
            </el-descriptions-item>
          </el-descriptions>

          <h4 class="section">处置时间线</h4>
          <!-- 时间线用 el-timeline 而不是表格：它的语义就是「按时间发生的动作序列」 -->
          <el-timeline>
            <el-timeline-item
              v-for="(t, i) in detail.timeline"
              :key="i"
              :timestamp="t.time"
              placement="top"
            >
              <div class="tl-action">{{ label(ACTION_LABELS, t.action) }}</div>
              <div class="mk-muted tl-meta">
                {{ t.operator || '系统' }}
                <template v-if="t.comment"> · {{ t.comment }}</template>
              </div>
            </el-timeline-item>
            <el-empty v-if="!detail.timeline?.length" description="暂无处置记录" :image-size="50" />
          </el-timeline>

          <template v-if="availableActions.length">
            <h4 class="section">处置</h4>
            <el-input
              v-model="actionComment"
              type="textarea"
              :rows="2"
              placeholder="处置意见（可选）"
              maxlength="200"
              show-word-limit
            />
            <div class="actions">
              <el-button
                v-for="a in availableActions"
                :key="a"
                size="small"
                :type="a === 'resolve' || a === 'misreport' ? 'danger' : 'primary'"
                @click="doAction(a)"
              >
                {{ label(ACTION_LABELS, a) }}
              </el-button>
            </div>
          </template>
          <el-alert v-else-if="detail" type="info" :closable="false" show-icon title="该警情已处于终态，不再受理处置" />
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.alarm-page {
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

.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--mk-border);
}

.alarm-page :deep(.el-table__row) {
  cursor: pointer;
}

.time {
  font-size: 12px;
  color: var(--mk-text-sub);
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 10px 16px;
  border-top: 1px solid var(--mk-border);
}

.section {
  margin: 20px 0 10px;
  font-size: 13px;
  font-weight: 600;
}

.tl-action {
  font-size: 13px;
}

.tl-meta {
  margin-top: 2px;
  font-size: 12px;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}
</style>
