<script setup>
import { onMounted, ref, watch } from 'vue'

import * as api from '@/api/monitor'
// 只取配色表：动作名本身就是中文（`@AuditAction` 里写的），直接显示即可，不走 label()
import { AUDIT_ACTION_TAG } from '@/utils/labels'
import { fromNow } from '@/utils/format'

/**
 * 审计日志（验收第 7 条后半：操作留痕可查）。
 *
 * 整个 `/audit-logs` 控制器挂着**类级** `@PreAuthorize("hasRole('ADMIN')")`，
 * 所以非管理员一律 403——菜单只对 ADMIN 显示，但真正的边界在后端。
 *
 * 这一页读的是**已经写好的东西**：`@AuditAction` 在各写接口上把动作、目标、操作人
 * 落进 `audit_log`。所以页面本身没什么逻辑，价值在于它让那些行**看得见**——
 * 尤其影像删除改成逻辑删除之后（行还在库里、界面上不见了），审计是唯一能回答
 * 「这张图为什么不见了」的地方。
 */

defineOptions({ name: 'AuditView' })

const rows = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(20)
const loading = ref(false)

/** 筛选：两个都是**精确匹配**（后端是 eq 不是 like），输错一个字就是空列表 */
const fUsername = ref('')
const fTargetType = ref('')

/**
 * 目标类型下拉的候选值。
 *
 * **不是枚举，是「代码里当前能产生的全部值」**：`AuditAspect.resolveTargetType` 取
 * ① 第一个 `Identifiable` 参数的类名，或 ② 控制器名去掉 `Controller`。
 * 所以这个集合 = `BaseCrudController` 的 7 个子类 + 自己挂 `@AuditAction` 的两个控制器。
 * 库里实测出现的只有其中 6 个（Alarm/AlarmRule/Metric/Organization/MaintenanceRecord
 * 那几个端点还没被调过）。
 *
 * 仍然给 `allow-create`：`target_type` 是自由文本，新增一个 `@AuditAction` 就会多一个值，
 * 而**筛不到不等于没记录**——卡死在这里会让人误以为没发生过。
 */
const TARGET_TYPES = [
  // BaseCrudController 的 7 个子类
  'MonitorPoint', 'Device', 'Project', 'Scene', 'MonitorObject', 'Metric', 'Organization',
  // 自己挂 @AuditAction 的
  'MaintenanceRecord', // MaintenanceRecordController.create
  'Media', // MediaController.delete（无 Identifiable 参数，走控制器名兜底）
]

async function load() {
  loading.value = true
  try {
    const data = await api.listAuditLogs({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      username: fUsername.value.trim() || undefined,
      targetType: fTargetType.value || undefined,
    })
    rows.value = data?.records || []
    total.value = data?.total || 0
  } catch {
    // 403（非管理员直达）与 5xx 都由 http 拦截器统一提示，这里不清空已有行——
    // 清空会让人以为「本来就没有记录」
  } finally {
    loading.value = false
  }
}

/** 改筛选条件必须回到第 1 页：停在第 3 页筛出 2 条会得到一个空表格 */
function search() {
  pageNum.value = 1
  load()
}

function reset() {
  fUsername.value = ''
  fTargetType.value = ''
  search()
}

watch([pageNum, pageSize], load)
onMounted(load)

/**
 * 详情列的展示：`detail` 存的是请求参数 JSON 串（`AuditAspect` 序列化进去的）。
 * 空的时候列里留白，别显示 `{}` 或 `null` 这种噪声。
 */
function detailText(d) {
  if (!d) return ''
  const t = String(d).trim()
  if (!t || t === '{}' || t === 'null') return ''
  return t
}
</script>

<template>
  <div class="audit-page">
    <div class="mk-panel">
      <div class="mk-panel-title">
        审计日志
        <span class="mk-muted title-sub">共 {{ total }} 条</span>
        <span class="mk-spacer" />
        <el-button size="small" link type="primary" @click="load">刷新</el-button>
      </div>

      <div class="filters">
        <el-input
          v-model="fUsername"
          placeholder="操作人（精确匹配）"
          clearable
          class="f-item"
          @keyup.enter="search"
        />
        <el-select
          v-model="fTargetType"
          placeholder="目标类型"
          clearable
          filterable
          allow-create
          class="f-item"
        >
          <el-option v-for="t in TARGET_TYPES" :key="t" :label="t" :value="t" />
        </el-select>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" size="small" class="audit-table">
        <el-table-column label="时间" width="180">
          <template #default="{ row }">
            <div class="mk-mono time">{{ row.createdAt }}</div>
            <div class="mk-muted ago">{{ fromNow(row.createdAt) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作人" width="110">
          <template #default="{ row }">
            <span class="mk-mono">{{ row.username || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="动作" width="150">
          <template #default="{ row }">
            <el-tag :type="AUDIT_ACTION_TAG[row.action] || 'info'" size="small">
              {{ row.action }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="目标" width="180">
          <template #default="{ row }">
            <span class="mk-muted type">{{ row.targetType || '—' }}</span>
            <!-- targetId 可能为空：不是所有 @AuditAction 都声明得了目标 id，
                 缺的时候别显示 "null" -->
            <span v-if="row.targetId" class="mk-mono id">{{ row.targetId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="细节" min-width="220">
          <template #default="{ row }">
            <span v-if="detailText(row.detail)" class="mk-mono detail" :title="detailText(row.detail)">
              {{ detailText(row.detail) }}
            </span>
            <span v-else class="mk-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="来源 IP" width="140">
          <template #default="{ row }">
            <span class="mk-mono ip">{{ row.ip || '—' }}</span>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty :image-size="60" :description="fUsername || fTargetType ? '没有符合条件的记录（注意两个筛选都是精确匹配）' : '还没有审计记录'" />
        </template>
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

    <div class="mk-panel">
      <div class="mk-footnote">
        写操作由后端 <span class="mk-mono">@AuditAction</span> 注解自动留痕，前端不参与——
        前端能少记一笔，就少一条「谁干的」查不出来。
        <strong>两个筛选条件都是精确匹配</strong>（后端是 <span class="mk-mono">eq</span> 不是
        <span class="mk-mono">like</span>），输错一个字就是空列表，不会「差不多匹配」。
        「目标」里的 <span class="mk-mono">targetId</span> 可能为空：不是每个写接口都能确定
        目标 id（例如按条件批量操作），空着比填一个猜的值好。
        <strong>本页只对管理员开放</strong>，后端类级 <span class="mk-mono">@PreAuthorize</span> 定的。
      </div>
    </div>
  </div>
</template>

<style scoped>
.audit-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.title-sub {
  margin-left: 10px;
  font-size: 12px;
  font-weight: 400;
}

.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.f-item {
  width: 220px;
}

.audit-table {
  width: 100%;
}

.time {
  font-size: 12px;
}

.ago {
  font-size: 11px;
}

.type {
  margin-right: 6px;
}

.id {
  font-size: 12px;
}

.detail {
  font-size: 12px;
  word-break: break-all;
  /* 请求参数 JSON 可能很长，截断到一行，完整内容走 title */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.ip {
  font-size: 12px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
