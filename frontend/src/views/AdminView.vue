<script setup>
import { onMounted, ref } from 'vue'

import http from '@/api/http'

/**
 * 平台管理端（需求 §5 第 1 类页面 / 验收第 7 条
 * 「平台管理端新建测点/规则 → 业务端无需改代码立即可见」）。
 *
 * 当前只做**只读表格**：把后端已有的档案资源列出来，证明
 * 「后端 BaseCrudController 的列表端点已经能被前端消费」这条链路是通的。
 * 新建/编辑/删除按钮尚未接——写操作要 ADMIN，且表单字段多（项目/场景/
 * 对象/测点四层外键），等下一轮单独做，不在这里放几个点不动的按钮。
 */

defineOptions({ name: 'AdminView' })

const TABS = [
  { key: 'projects', label: '项目', path: '/v1/projects' },
  { key: 'scenes', label: '场景', path: '/v1/scenes' },
  { key: 'objects', label: '监测对象', path: '/v1/objects' },
  { key: 'points', label: '测点', path: '/v1/points' },
  { key: 'metrics', label: '测项', path: '/v1/metrics' },
  { key: 'devices', label: '设备', path: '/v1/devices' },
  { key: 'alarm-rules', label: '告警规则', path: '/v1/alarm-rules' },
]

const active = ref('projects')
const rows = ref([])
const loading = ref(false)
const columns = ref([])

/**
 * 列表形状有两种（契约有意为之）：`/alarms` 是分页对象，其余是裸数组。
 * 这里统一成数组，新增资源时不必再改模板。
 */
function normalize(data) {
  if (Array.isArray(data)) return data
  return data?.records || []
}

async function load() {
  loading.value = true
  try {
    const tab = TABS.find((t) => t.key === active.value)
    rows.value = normalize(await http.get(tab.path))
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

/** 表头由数据推导：各资源字段不同，写死列在后端加字段时就看不到了 */
function pickColumns(list) {
  if (!list.length) {
    columns.value = []
    return
  }
  const keys = Object.keys(list[0]).filter((k) => k !== 'deleted')
  columns.value = keys.slice(0, 9).map((k) => ({
    key: k,
    label: k,
    wide: typeof list[0][k] === 'string' && list[0][k].length > 24,
  }))
}

/** 切页签和首次进入走同一条路：先取数，再按取到的数据推列 */
async function reload() {
  await load()
  pickColumns(rows.value)
}

onMounted(reload)
</script>

<template>
  <div class="mk-panel admin-page">
    <div class="mk-panel-title">
      平台管理
      <span class="mk-muted title-sub">只读 · 写操作下一轮实现</span>
      <span class="mk-spacer" />
      <el-button size="small" link type="primary" @click="reload">刷新</el-button>
    </div>

    <el-tabs v-model="active" class="tabs" @tab-change="reload">
      <el-tab-pane v-for="t in TABS" :key="t.key" :label="t.label" :name="t.key" />
    </el-tabs>

    <el-table :data="rows" v-loading="loading" size="small">
      <el-table-column
        v-for="c in columns"
        :key="c.key"
        :prop="c.key"
        :label="c.label"
        :min-width="c.wide ? 220 : 110"
        show-overflow-tooltip
      />
      <template #empty>
        <el-empty description="该资源暂无数据" :image-size="60" />
      </template>
    </el-table>
  </div>
</template>

<style scoped>
.admin-page {
  display: flex;
  flex-direction: column;
}

.title-sub {
  margin-left: 10px;
  font-size: 12px;
  font-weight: 400;
}

.tabs {
  padding: 0 16px;
}

.tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}
</style>
