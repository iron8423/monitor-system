<script setup>
import { computed, onMounted, ref } from 'vue'

import { opsConfig, opsMigrations, opsStats, opsStatus } from '@/api/ops'
import { listProjects, projectDigitalTwin } from '@/api/monitor'
import { useIntegrityStore } from '@/stores/integrity'
import { HASH_TEXT, isHashProblem } from '@/utils/assetHash'
import { formatNumber, formatTime, fromNow } from '@/utils/format'

/**
 * 系统运维（仅 ADMIN）。
 *
 * 分工要说清楚，否则这一页会被当成"第二个管理端"：
 *   · `/admin` —— 管**数据**：项目/场景/测点/设备/测项/规则/用户，可增删改查；
 *   · 本页    —— 看**服务**：进程与 JVM、数据库与连接池、Flyway 迁移、数据规模、
 *                以及会影响行为的运行时开关。**全部只读**。
 *
 * 只读是刻意的：备份、清理、重跑迁移这些动作留在命令行（`tools/backup/*`、
 * `docker compose exec`），因为它们的失败代价远高于"看一眼"，而且它们需要宿主机权限，
 * 不该由一个 HTTP 接口代劳。这一页的价值是让人**不必 SSH 就能回答**：
 * "服务活着吗、连的哪个库、迁移到哪一版、攒了多少数据、生产开关对不对"。
 */

defineOptions({ name: 'OpsView' })

const loading = ref(false)
const error = ref('')
const status = ref(null)
const migrations = ref(null)
const stats = ref(null)
const config = ref(null)
const loadedAt = ref('')
const projectNames = ref({})

/**
 * 资产哈希核对（复查清单 P0-5）。
 *
 * 核对本身在浏览器里做（模型文件是前端静态资源，后端进程读不到），结果存 integrity store。
 * 大屏加载模型时也会往同一个 store 里写——两处读同一份结论，避免"大屏报红、运维页说正常"。
 *
 * 这里**额外**做一件大屏不做的事：把全部启用了数字孪生的项目都核一遍。
 * 大屏只核对当前选中的那一个，值班员不切项目就永远不知道另一份资产有没有被动过。
 */
const integrity = useIntegrityStore()
const assetLoading = ref(false)
const assetError = ref('')

const assetRows = computed(() =>
  integrity.results.map((r) => ({
    ...r,
    projectName: projectNames.value[r.projectId] || `项目 ${r.projectId}`,
    statusText: HASH_TEXT[r.status] || r.status,
  })),
)

const assetSummary = computed(() => {
  const rows = assetRows.value
  if (!rows.length) return '尚未核对'
  const bad = rows.filter((r) => isHashProblem(r.status)).length
  const ok = rows.filter((r) => r.status === 'ok').length
  return bad > 0 ? `${bad} 项需要处理（共核对 ${rows.length} 项）` : `全部一致（${ok}/${rows.length}）`
})

/** 拉全部项目 → 逐个取场景配置 → 只核对启用中的 GLB（3D Tiles 见 utils/assetHash 的说明）。 */
async function checkAssets() {
  assetLoading.value = true
  assetError.value = ''
  try {
    const projects = await listProjects()
    projectNames.value = Object.fromEntries((projects || []).map((p) => [p.id, p.name]))
    const cfgs = await Promise.all(
      (projects || []).map((p) => projectDigitalTwin(p.id).catch(() => null)),
    )
    await integrity.verifyAll(cfgs.filter((c) => c?.enabled && c?.assetUrl))
  } catch (e) {
    assetError.value = e?.message || '资产核对失败'
  } finally {
    assetLoading.value = false
  }
}

const uptimeText = computed(() => {
  const seconds = status.value?.uptimeSeconds
  if (!Number.isFinite(seconds)) return '—'
  const days = Math.floor(seconds / 86400)
  const hours = Math.floor((seconds % 86400) / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  if (days > 0) return `${days} 天 ${hours} 小时`
  if (hours > 0) return `${hours} 小时 ${minutes} 分`
  return `${minutes} 分`
})

/** 服务/数据库/开关三类"体检结论"，把需要人注意的项直接说成一句话。 */
const notices = computed(() => {
  const list = []
  if (config.value?.ingestKeyIsDevDefault) {
    list.push({ level: 'warning', text: '接入密钥仍是开发默认值：生产部署必须用 MONITOR_INGEST_KEY 覆盖' })
  }
  if (config.value && !config.value.strictContract) {
    list.push({ level: 'info', text: '严格契约模式当前关闭（本地/演示形态的正常状态；生产编排会强制打开）' })
  }
  if (migrations.value?.pendingCount > 0) {
    list.push({ level: 'warning', text: `有 ${migrations.value.pendingCount} 条迁移待执行：重启后端即会应用` })
  }
  const failed = (migrations.value?.applied || []).filter((m) => m.state !== 'SUCCESS')
  if (failed.length) {
    list.push({ level: 'error', text: `有 ${failed.length} 条迁移不是 SUCCESS 状态，请立刻检查` })
  }
  return list
})

const statRows = computed(() => {
  const counts = stats.value?.counts || {}
  const labels = {
    project: '项目',
    scene: '场景',
    monitor_point: '测点',
    metric: '测项',
    device: '设备',
    device_point: '设备-测点绑定',
    measurement: '测量值',
    alarm: '警情',
    alarm_action: '处置记录',
    media: '影像',
    audit_log: '审计日志',
    sys_user: '账号',
  }
  return Object.entries(counts).map(([key, value]) => ({
    key,
    label: labels[key] || key,
    // P2-9：大表（目前只有 measurement）走数据库统计信息估算——**必须标「约」**，
    // 否则估算值的正常浮动会被读成"数据少了"。近似表由后端在 approximateTables 里列出；
    // 但点了「精确计数一次」之后返回的是 COUNT(*) 真值（响应里 exact=true），
    // 那时再顶着「约」字就是误导——标注要跟着口径走，不能跟着表名走。
    value,
    approximate: !stats.value?.exact && (stats.value?.approximateTables || []).includes(key),
  }))
})

const configRows = computed(() => {
  const c = config.value
  if (!c) return []
  return [
    { label: '严格契约模式', value: c.strictContract ? '开启（生产形态）' : '关闭（本地/演示）' },
    { label: '单批消息上限', value: `${c.maxBatchSize} 条` },
    { label: '采集时间超前容差', value: `${c.maxCollectAheadSeconds} 秒` },
    { label: '接收时间超前容差', value: `${c.maxReceiveAheadSeconds} 秒` },
    { label: '影像上传目录', value: c.uploadDir },
    { label: '演示账号初始化', value: c.demoAccountsEnabled ? '开启' : '关闭（生产默认）' },
    { label: '令牌有效期', value: `${c.jwtExpirationHours} 小时` },
    {
      // 时间口径（2026-09-18 的真 bug 之后加的）：JVM 默认时区由应用钉死，
      // 与宿主机 TZ 无关。放在这一页是因为"这台机器的时间基准是什么"是排障第一问——
      // 裸机漏配 TZ 时，接入会整批被判成"未来"。
      label: '时间口径',
      value: `${c.timeZone}（与宿主机 TZ 无关）`,
    },
    {
      // 保留任务（P1-1）：这是"会不会删数据"的第一现场，必须出现在运行时开关里。
      // 开着的时候把保留天数与最近一轮删了多少一起写出来——只看"开启"两个字说明不了任何事。
      label: '测量值保留',
      value: c.retention?.enabled
        ? `开启 · 保留 ${c.retention.rawDays} 天`
          + (c.retention.lastRun ? ` · 上轮删除 ${formatNumber(c.retention.lastRun.deleted, 0)} 行` : ' · 尚未执行')
        : '关闭（默认，不会删除任何数据）',
    },
    {
      label: '接入密钥',
      value: `长度 ${c.ingestKeyLength} 位 · ${c.ingestKeyIsDevDefault ? '开发默认值（需覆盖）' : '已按环境变量覆盖'}`,
    },
  ]
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [s, m, st, c] = await Promise.all([
      opsStatus(),
      opsMigrations(),
      opsStats(),
      opsConfig(),
    ])
    status.value = s
    migrations.value = m
    stats.value = st
    config.value = c
    loadedAt.value = new Date().toISOString()
  } catch (e) {
    error.value = e?.message || '运维信息加载失败'
  } finally {
    loading.value = false
  }
}

/**
 * 精确计数一次（P2-9）：默认口径里 measurement 走数据库统计信息估算（标「约」），
 * 需要真值时由人显式点一次——亿级表上的 COUNT(*) 不该被页面自动触发。
 */
const exactLoading = ref(false)
async function loadExactCounts() {
  exactLoading.value = true
  try {
    stats.value = await opsStats(true)
  } catch (e) {
    error.value = e?.message || '精确计数失败'
  } finally {
    exactLoading.value = false
  }
}

onMounted(() => {
  load()
  // 资产核对与运维信息并行：它要下载几个 MB 的模型，不该拖住首屏那几个数字。
  // 失败也不算页面错误——上面那张卡片自己会写清楚。
  checkAssets()
})
</script>

<template>
  <div class="ops-page" v-loading="loading">
    <el-alert
      v-if="error"
      class="ops-alert"
      type="error"
      :title="error"
      :closable="false"
      show-icon
    />

    <el-alert
      v-for="notice in notices"
      :key="notice.text"
      class="ops-alert"
      :type="notice.level === 'info' ? 'info' : notice.level"
      :title="notice.text"
      :closable="false"
      show-icon
    />

    <div class="tiles">
      <div class="mk-panel tile">
        <div class="tile-label">服务状态</div>
        <div class="tile-value ok">{{ status?.status || '—' }}</div>
        <div class="tile-sub">
          {{ status?.service || '—' }} · {{ status?.profile || 'default' }} profile
        </div>
      </div>
      <div class="mk-panel tile">
        <div class="tile-label">运行时长</div>
        <div class="tile-value">{{ uptimeText }}</div>
        <div class="tile-sub">启动于 {{ status?.startedAt ? formatTime(status.startedAt) : '—' }}</div>
      </div>
      <div class="mk-panel tile">
        <div class="tile-label">堆内存</div>
        <div class="tile-value">
          {{ status?.heapUsedMb ?? '—' }}<span class="unit"> / {{ status?.heapMaxMb ?? '—' }} MB</span>
        </div>
        <div class="tile-sub">Java {{ status?.javaVersion || '—' }} · {{ status?.availableProcessors || '—' }} 核</div>
      </div>
      <div class="mk-panel tile">
        <div class="tile-label">实时推送连接</div>
        <div class="tile-value">{{ status?.sseClients ?? '—' }}</div>
        <div class="tile-sub">数据库连接池 {{ status?.pool?.active ?? '—' }}/{{ status?.pool?.max ?? '—' }} 在用</div>
      </div>
    </div>

    <div class="mk-panel">
      <div class="mk-panel-title">
        资产哈希核对
        <span class="mk-spacer" />
        <span class="mk-muted title-sub">{{ assetSummary }}</span>
        <el-button size="small" link type="primary" :loading="assetLoading" @click="checkAssets">
          全部核对
        </el-button>
      </div>
      <el-alert
        v-if="assetError"
        class="ops-alert"
        type="error"
        :title="assetError"
        :closable="false"
        show-icon
      />
      <el-table :data="assetRows" size="small" class="ops-table">
        <el-table-column prop="projectName" label="项目" min-width="180" show-overflow-tooltip />
        <el-table-column prop="assetUrl" label="资产" min-width="240" show-overflow-tooltip />
        <el-table-column label="状态" width="220">
          <template #default="{ row }">
            <el-tag :type="isHashProblem(row.status) ? 'danger' : (row.status === 'ok' ? 'success' : 'info')" size="small">
              {{ row.statusText }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="登记 / 实测" min-width="240">
          <template #default="{ row }">
            <span class="mk-mono">
              {{ row.expected ? row.expected.slice(0, 12) : '—' }}
              /
              {{ row.actual ? row.actual.slice(0, 12) : '—' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="核对时间" width="170">
          <template #default="{ row }">{{ row.checkedAt ? formatTime(row.checkedAt) : '—' }}</template>
        </el-table-column>
        <el-table-column label="说明" min-width="200">
          <template #default="{ row }">{{ row.error || `${row.bytes ? formatNumber(row.bytes) + ' 字节' : ''}` }}</template>
        </el-table-column>
      </el-table>
      <div class="ops-hint">
        核对在浏览器里做：模型文件是前端静态资源，后端进程读不到；比对的是本机此刻加载到的那一份
        与数据库登记的 asset_sha256。不一致说明盘上的模型与平台记录不是同一份，先确认哪一个是权威值。
      </div>
    </div>

    <div class="mk-panel">
      <div class="mk-panel-title">
        数据库与迁移
        <span class="mk-spacer" />
        <span class="mk-muted title-sub">当前版本 {{ migrations?.currentVersion || '—' }}</span>
        <el-button size="small" link type="primary" @click="load">刷新</el-button>
        <a class="doc-link" href="/swagger-ui/index.html" target="_blank" rel="noopener">接口文档</a>
      </div>
      <div class="db-line">
        <span class="mk-muted">数据库</span>
        <span class="mk-mono">{{ status?.database?.product || '—' }}</span>
        <span class="mk-muted">连接串</span>
        <span class="mk-mono">{{ status?.database?.url || '—' }}</span>
      </div>
      <el-table :data="migrations?.applied || []" size="small" class="ops-table">
        <el-table-column prop="version" label="版本" width="90" />
        <el-table-column prop="description" label="说明" min-width="220" show-overflow-tooltip />
        <el-table-column prop="script" label="脚本" min-width="240" show-overflow-tooltip />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.state === 'SUCCESS' ? 'success' : 'danger'" size="small">
              {{ row.state }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="执行时间" width="180">
          <template #default="{ row }">{{ row.installedOn ? formatTime(row.installedOn) : '—' }}</template>
        </el-table-column>
        <el-table-column label="耗时" width="90">
          <template #default="{ row }">{{ row.executionTimeMs }} ms</template>
        </el-table-column>
      </el-table>
      <div class="mk-footnote">
        迁移由 Flyway 在应用启动时自动执行。<strong>不要手工改已经执行过的迁移</strong>——改了会校验和不匹配、
        后端直接起不来（2026-09-18 踩过一次；处理办法：先对齐校验和，再新开一个版本号写新迁移）。
      </div>
    </div>

    <div class="two-col">
      <div class="mk-panel">
        <div class="mk-panel-title">
          数据规模
          <span class="mk-spacer" />
          <span class="mk-muted title-sub">截至 {{ loadedAt ? fromNow(loadedAt) : '—' }}</span>
          <!-- 精确计数由人显式触发一次：亿级表上的 COUNT(*) 不该被页面自动打出去（P2-9） -->
          <el-button size="small" link type="primary" :loading="exactLoading" @click="loadExactCounts">
            精确计数一次
          </el-button>
        </div>
        <el-table :data="statRows" size="small" class="ops-table">
          <el-table-column prop="label" label="表 / 实体" min-width="150" />
          <el-table-column label="行数" width="120" align="right">
            <template #default="{ row }">
              <span class="mk-mono">
                <span v-if="row.approximate" class="mk-muted" :title="stats?.note">约 </span>
                {{ formatNumber(row.value, 0) }}
              </span>
            </template>
          </el-table-column>
        </el-table>
        <div class="mk-footnote">
          <strong>行数是物理行数</strong>（含逻辑删除留下的墓碑行，所以会比界面上看到的多）。
          测量值 {{ formatNumber(stats?.measurement?.realtimeRows, 0) }} 条实时 /
          {{ formatNumber(stats?.measurement?.backfillRows, 0) }} 条补报；
          时间跨度 {{ stats?.measurement?.earliestCollectTime || '—' }}
          ~ {{ stats?.measurement?.latestCollectTime || '—' }}。
          未解除警情 {{ formatNumber(stats?.measurement?.openAlarms, 0) }} 条
          （其中设备类 {{ formatNumber(stats?.measurement?.deviceAlarms, 0) }} 条）。
        </div>
      </div>

      <div class="mk-panel">
        <div class="mk-panel-title">运行配置</div>
        <el-table :data="configRows" size="small" class="ops-table">
          <el-table-column prop="label" label="开关" min-width="150" />
          <el-table-column prop="value" label="当前值" min-width="180" show-overflow-tooltip />
        </el-table>
        <div class="mk-footnote">
          密钥只回显「是否仍是默认值」，不回显内容本身。其中两项与生产强相关：
          <strong>严格契约</strong>必须打开、<strong>接入密钥</strong>必须用环境变量覆盖。
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ops-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.ops-alert {
  margin-bottom: 0;
}

.tiles {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 14px;
}

.tile {
  padding: 14px 16px;
}

.tile-label {
  font-size: 13px;
  color: var(--mk-text-sub);
}

.tile-value {
  margin: 6px 0 4px;
  font-size: 24px;
  font-weight: 600;
  color: var(--mk-primary);
}

.tile-value.ok {
  color: var(--mk-online);
}

.tile-value .unit {
  font-size: 13px;
  font-weight: 400;
  color: var(--mk-text-sub);
}

.tile-sub {
  font-size: 12px;
  color: var(--mk-text-sub);
}

.db-line {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  padding: 10px 16px 0;
  font-size: 12px;
}

/* 表格下方的口径说明：这页的每个数字都可能被误读，所以宁可多一句解释 */
.ops-hint {
  padding: 10px 16px 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--mk-text-sub);
}

.doc-link {
  margin-left: 10px;
  font-size: 13px;
  color: var(--mk-primary);
}

.ops-table {
  width: 100%;
}

.two-col {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(420px, 1fr));
  gap: 14px;
}
</style>
