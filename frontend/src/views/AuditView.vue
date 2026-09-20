<script setup>
import { onMounted, ref, watch } from 'vue'

import * as api from '@/api/monitor'
// 只取配色表：动作名本身就是中文（`@AuditAction` 里写的），直接显示即可，不走 label()
import { AUDIT_ACTION_TAG } from '@/utils/labels'
import { formatTime, fromNow } from '@/utils/format'

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
 *
 * 三条口径（原先挂在页面底部的说明块里，2026-09-18 按用户要求把说明块去掉，
 * 依据挪到这里，免得下次有人把它当"没用的文案"删掉）：
 *   · 留痕由后端 `@AuditAction` 自动完成，前端不参与——前端能少记一笔，就少一条「谁干的」查不出来；
 *   · 「目标」列里的 `targetId` 可能为空：不是每个写接口都能确定目标 id（例如按条件批量操作），
 *     空着比填一个猜的值好；
 *   · 本页只对管理员开放，边界由后端**类级** `@PreAuthorize` 定，菜单隐藏只是界面引导。
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

/**
 * 「变更」列的展示（P1-8 / V23）：把 `before_json` 与 `after_json` 压成一两行
 * 「字段: 旧 → 新」。只列**真的变了**的字段——整行 JSON 贴出来没人会读，
 * 而审计的价值恰恰是「一眼看出这次改了什么」。
 *
 * 三种退化形状都如实说出来，不装作没有：
 *   · 只有 after  → 新建（原来没有这一行）；
 *   · 只有 before → 删除（现在没有这一行了）；
 *   · 两端都没有  → 这类端点拿不到行级快照（复合键端点，见 AuditSnapshotSource 的注释）。
 */
function parseJson(s) {
  if (!s) return null
  try {
    const v = JSON.parse(s)
    return v && typeof v === 'object' ? v : null
  } catch {
    return null
  }
}

function changeLines(row) {
  const before = parseJson(row.beforeJson)
  const after = parseJson(row.afterJson)
  if (!before && !after) return []
  if (!before && after) return [{ key: '新建', from: '', to: `#${after.id ?? '?'}` }]
  if (before && !after) return [{ key: '删除', from: `#${before.id ?? '?'}`, to: '' }]
  // 只比两边都出现的字段：只在一侧出现的字段说明"这次没带它"（updateById 跳过 null），
  // 当成"改成空"会得出错误结论——本仓对这类"入参不等于全量"的坑已经踩过不止一次。
  const keys = [...new Set([...Object.keys(before), ...Object.keys(after)])]
  const lines = []
  for (const k of keys) {
    if (k === 'updatedAt' || k === 'createdAt') continue // 时间戳每次都会变，列出来只是噪声
    const a = before[k]
    const b = after[k]
    if (JSON.stringify(a) === JSON.stringify(b)) continue
    if (b === undefined) continue
    lines.push({ key: k, from: fmt(a), to: fmt(b) })
  }
  return lines.slice(0, 4)
}

function fmt(v) {
  if (v === undefined) return '—'
  if (v === null) return '空'
  if (typeof v === 'object') return Array.isArray(v) ? `[${v.length} 项]` : '{…}'
  const s = String(v)
  return s.length > 24 ? `${s.slice(0, 24)}…` : s
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
            <!--
              时间要格式化再显示：后端给的是带纳秒的 ISO8601（如
              2026-09-17T15:00:38.411855+08:00），原样贴出来在 180px 的列里会折成三行，
              而且没人会去读那六位小数。与其余页面统一走 formatTime。
            -->
            <div class="mk-mono time">{{ formatTime(row.createdAt) }}</div>
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
        <!-- 结果：SUCCESS / FAILED。旧数据（V23 之前）在迁移里回填成 SUCCESS，
             所以空值只可能出现在"迁移后某个写路径忘了填"的情形，显示成「—」比假装成功好 -->
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag
              :type="row.result === 'FAILED' ? 'danger' : (row.result === 'SUCCESS' ? 'success' : 'info')"
              size="small"
            >
              {{ row.result || '—' }}
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
        <!-- 变更（P1-8）：一句话说清"改了什么"，比整段入参 JSON 有用得多 -->
        <el-table-column label="变更" min-width="260">
          <template #default="{ row }">
            <div v-if="row.result === 'FAILED'" class="fail-line">
              <span class="mk-muted">被拒：</span>
              <span class="reason">{{ row.errorMessage || '（无原因）' }}</span>
            </div>
            <div v-for="line in changeLines(row)" :key="line.key" class="change-line mk-mono">
              <span class="k">{{ line.key }}</span>
              <span class="mk-muted from">{{ line.from || '—' }}</span>
              <span class="mk-muted">→</span>
              <span class="to">{{ line.to || '—' }}</span>
            </div>
            <span v-if="!changeLines(row).length && row.result !== 'FAILED'" class="mk-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="细节（入参）" min-width="200">
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
  /* 格式化后是「2026-09-17 15:00:38」，禁止折行——这一列的价值就是一眼能读 */
  white-space: nowrap;
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

/* 变更行的排版（P1-8）：字段名固定宽度，旧值划掉、新值高亮，扫一眼就知道改了什么 */
.change-line {
  display: flex;
  gap: 6px;
  align-items: baseline;
  font-size: 12px;
  line-height: 1.7;
}

.change-line .k {
  min-width: 88px;
  color: var(--mk-text-sub);
}

.change-line .from {
  text-decoration: line-through;
}

.change-line .to {
  color: var(--mk-primary);
}

.fail-line {
  font-size: 12px;
  line-height: 1.7;
}

.fail-line .reason {
  color: #f56c6c;
  word-break: break-all;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
