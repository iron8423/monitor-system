<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import http from '@/api/http'
import { LEVEL_LABELS, ROLE_LABELS, label } from '@/utils/labels'

/**
 * 平台管理端（需求 §5 第 1 类页面 / 验收第 7 条
 * 「平台管理端新建测点/规则 → 业务端无需改代码立即可见」）。
 *
 * 表格列由**响应数据推导**（各资源字段不同，写死列在后端加字段时就看不到了），
 * 表单则由下面的 TABS[].fields 声明——两者刻意分开：表列是「后端有什么」，
 * 表单是「允许人填什么」，后者要显式，不能让前端把 id/createdAt 也提交上去。
 *
 * 写操作全部走后端 BaseCrudController 的 POST/PUT/DELETE，它们都带
 * `@PreAuthorize("hasRole('ADMIN')")`；非 ADMIN 进来会拿 403（本页菜单也按角色过滤）。
 */

defineOptions({ name: 'AdminView' })

/** 外键下拉的数据源：`ref` 名 → 取数路径与展示文案 */
const REFS = {
  organizations: { path: '/v1/organizations', text: (r) => r.name },
  projects: { path: '/v1/projects', text: (r) => `${r.name}（${r.code}）` },
  scenes: { path: '/v1/scenes', text: (r) => r.name },
  objects: { path: '/v1/objects', text: (r) => r.name },
  points: { path: '/v1/points', text: (r) => `${r.code} — ${r.name}` },
  /**
   * 测项**码**（字符串，不是 id）：给告警规则的「测项」下拉用。
   * `/v1/metrics` 是「每点一行」，同一个 code 会出现很多次——按 code 去重，
   * 值取 `code` 而不是 `id`（规则里存的本来就是 metric_code）。
   * 这样加一种测项时，管理端下拉会**自动多一项**，不必再来改这里写死的数组。
   */
  metricCodes: {
    path: '/v1/metrics',
    text: (r) => `${r.code}（${r.name}${r.unit ? ' · ' + r.unit : ''}）`,
    valueKey: 'code',
    dedupeBy: 'code',
  },
}

/**
 * 枚举取值不是猜的，是从现行库/代码里核出来的：
 * scene.type、object.type、point.type、device.type、operator、level 各取实际在用值。
 * 后两个的合法集在 AlarmRuleService#validate 里，收窄成提示里那几种。
 */
const TABS = [
  {
    key: 'projects',
    label: '项目',
    path: '/v1/projects',
    fields: [
      { key: 'organizationId', label: '所属组织', type: 'ref', ref: 'organizations', required: true },
      { key: 'name', label: '项目名称', required: true },
      { key: 'code', label: '项目编码', required: true },
      { key: 'location', label: '位置' },
      { key: 'description', label: '描述', type: 'textarea' },
    ],
  },
  {
    /*
     * 用户管理（清单第 13 条）。按用户 2026-09-17 的口径，管理员在这一页**只做三件事**：
     * 看全部账号、改**角色与启用**、删账号。
     *
     * 所以：
     *   · 没有「新建」——账号由本人自助注册（/register），信息也由本人填；
     *   · 编辑弹窗里**没有**姓名/公司/岗位/电话/邮箱——那是个人信息，本人自己在个人中心改；
     *   · `columns` 显式声明：用户表字段多，自动取前 8 个会把电话/邮箱挤掉。
     */
    key: 'users',
    label: '用户',
    path: '/v1/users',
    noCreate: true,
    fields: [
      { key: 'role', label: '角色', type: 'select', options: ['ADMIN', 'OPERATOR', 'ANALYST', 'MAINTAINER'], required: true },
      { key: 'enabled', label: '启用', type: 'switch', default: true, hint: '停用后对方手里的令牌下一个请求即失效（不必等过期）' },
    ],
    columns: [
      { key: 'username', label: '账号' },
      { key: 'displayName', label: '姓名' },
      { key: 'roleLabel', label: '角色' },
      { key: 'organizationName', label: '公司' },
      { key: 'jobTitle', label: '岗位' },
      { key: 'phone', label: '联系电话' },
      { key: 'email', label: '邮箱' },
      { key: 'enabled', label: '启用' },
    ],
  },
  {
    key: 'scenes',
    label: '场景',
    path: '/v1/scenes',
    fields: [
      { key: 'projectId', label: '所属项目', type: 'ref', ref: 'projects', required: true },
      { key: 'name', label: '场景名称', required: true },
      { key: 'type', label: '场景类型', type: 'select', options: ['SLOPE', 'ASH_SILO'], required: true },
      { key: 'description', label: '描述', type: 'textarea' },
    ],
  },
  {
    key: 'objects',
    label: '监测对象',
    path: '/v1/objects',
    fields: [
      { key: 'sceneId', label: '所属场景', type: 'ref', ref: 'scenes', required: true },
      { key: 'name', label: '对象名称', required: true },
      { key: 'type', label: '对象类型', type: 'select', options: ['SLOPE_BODY', 'SILO_BODY'], required: true },
      { key: 'description', label: '描述', type: 'textarea' },
    ],
  },
  {
    key: 'points',
    label: '测点',
    path: '/v1/points',
    fields: [
      { key: 'objectId', label: '所属对象', type: 'ref', ref: 'objects', required: true },
      {
        key: 'code',
        label: '点号',
        required: true,
        hint: '雷达报文靠点号定位测点，与上报口径必须一致',
      },
      { key: 'name', label: '测点名称', required: true },
      { key: 'type', label: '测点类型', type: 'select', options: ['POINT_DEFORMATION'], required: true },
      { key: 'longitude', label: '经度', type: 'number' },
      { key: 'latitude', label: '纬度', type: 'number' },
      // 测点自己的高程（这个点在空间里的位置）。**不是**雷达基座几何——
      // 那 7 个字段（朝向/俯仰/量程/半视场角/天线高度/基座高程）属于设备，在下面「设备」页签
      { key: 'altitude', label: '高程（m）', type: 'number' },
      { key: 'enabled', label: '启用', type: 'switch', default: true },
    ],
  },
  {
    key: 'metrics',
    label: '测项',
    path: '/v1/metrics',
    fields: [
      { key: 'pointId', label: '所属测点', type: 'ref', ref: 'points', required: true },
      { key: 'code', label: '测项码', required: true, hint: '如 defo_mm / rate_mm_d' },
      { key: 'name', label: '测项名称', required: true },
      { key: 'unit', label: '单位', hint: '如 mm / mm/d' },
      { key: 'sortOrder', label: '排序', type: 'number' },
    ],
  },
  {
    key: 'devices',
    label: '设备',
    path: '/v1/devices',
    fields: [
      { key: 'code', label: '设备编号', required: true },
      { key: 'name', label: '设备名称', required: true },
      { key: 'type', label: '设备类型', type: 'select', options: ['MILLIMETER_WAVE_RADAR'] },
      { key: 'serialNo', label: '序列号' },
      { key: 'longitude', label: '经度', type: 'number' },
      { key: 'latitude', label: '纬度', type: 'number' },
      // ---- 雷达安装几何。字段名与 asset/entity/Device.java 逐字一致，**别改名** ----
      // 这 7 个决定「雷达能不能看到某个目标」（`RadarCoveragePolicy`），也就是标定的
      // 空间判据。改动其中任何一个都会让已有标定失效（后端置 INVALID，
      // 见清单第 09 条）——改之前先想清楚要不要重标。留空不发 null，
      // `updateById` 跳过 null，所以只改名称时这里空着不会把已有几何清掉。
      { key: 'altitude', label: '基座高程（m）', type: 'number' },
      { key: 'headingDegrees', label: '朝向角（°）', type: 'number' },
      { key: 'pitchDegrees', label: '俯仰角（°）', type: 'number' },
      { key: 'detectionRangeM', label: '探测量程（m）', type: 'number' },
      { key: 'halfAngleDegrees', label: '水平半视场角（°）', type: 'number' },
      { key: 'verticalHalfAngleDegrees', label: '垂直半视场角（°）', type: 'number' },
      { key: 'antennaHeightM', label: '天线离地高度（m）', type: 'number' },
      {
        key: 'status',
        label: '状态标注',
        type: 'select',
        options: ['', 'FAULT'],
        hint: '只有 FAULT 有意义：在线/离线由最后上报时间推导，不在这里填',
      },
      { key: 'battery', label: '电量（%）', type: 'number' },
    ],
  },
  {
    key: 'alarm-rules',
    label: '告警规则',
    path: '/v1/alarm-rules',
    /**
     * 显式列（与「用户」页签同理）：规则的作用域现在有三个维度
     * （项目名 / 点号 / 都为空），自动取前 8 个键会把 value、level 这些
     * 真正要看的阈值列挤掉，只剩一串 id。
     */
    columns: [
      { key: 'id', label: 'ID' },
      { key: 'name', label: '规则名称' },
      { key: 'projectName', label: '适用项目' },
      { key: 'pointCode', label: '适用测点' },
      { key: 'metricCode', label: '测项' },
      { key: 'operator', label: '比较' },
      { key: 'value', label: '阈值' },
      { key: 'level', label: '等级' },
      { key: 'enabled', label: '启用' },
    ],
    fields: [
      { key: 'name', label: '规则名称', required: true },
      { key: 'pointId', label: '适用测点', type: 'ref', ref: 'points', hint: '留空 = 按下面的项目作用域' },
      {
        key: 'projectId',
        label: '适用项目',
        type: 'ref',
        ref: 'projects',
        hint: '留空且未选测点 = 全局规则（所有项目共用这套阈值，慎用）',
      },
      { key: 'metricCode', label: '测项', type: 'ref', ref: 'metricCodes', required: true },
      { key: 'type', label: '规则类型', type: 'select', options: ['THRESHOLD'], default: 'THRESHOLD' },
      { key: 'operator', label: '比较方式', type: 'select', options: ['gte', 'lte'], default: 'gte', required: true },
      { key: 'value', label: '阈值', type: 'number', required: true },
      { key: 'level', label: '告警等级', type: 'select', options: ['notice', 'warning', 'alarm'], default: 'warning' },
      { key: 'recoveryValue', label: '恢复阈值', type: 'number', hint: '留空则按阈值反向判恢复' },
      { key: 'repeatSuppressSeconds', label: '防刷屏（秒）', type: 'number' },
      { key: 'enabled', label: '启用', type: 'switch', default: true },
    ],
  },
]

const active = ref('projects')
const rows = ref([])
const loading = ref(false)
const error = ref('')
const columns = ref([])

const currentTab = computed(() => TABS.find((t) => t.key === active.value))

/** 下拉选项：只在当前页签需要时才拉，且拉过就不重复拉 */
const refOptions = reactive({})
const refLoading = ref(false)

/**
 * 列表形状有两种（契约有意为之）：`/alarms` 是分页对象，其余是裸数组。
 * 这里统一成数组，新增资源时不必再改模板。
 */
function normalize(data) {
  if (Array.isArray(data)) return data
  return data?.records || []
}

/**
 * 代际守卫（与 PointsView 的 `createRequestGuard` 同一套思路）。
 *
 * 为什么需要：切页签时 `reload()` 先把「当前页签」快照下来，再发请求；请求回来时页签可能
 * 已经变了（用户手比网络快）。此前这里读的是**响应到达那一刻**的 `currentTab`——
 * 2026-09-17 实测到的症状是：切到「用户」后表格里是 2 行、每格都是「—」，
 * 正是**上一个页签（项目，2 行）的数据**套在新的列定义上渲染出来的；
 * 点一下「刷新」（此时页签已稳定）就正常。旧响应必须丢掉，不能写进当前页签。
 */
let loadGeneration = 0

async function load(tab, token) {
  loading.value = true
  error.value = ''
  try {
    const data = await http.get(tab.path)
    if (token !== loadGeneration) return // 迟到的旧页签响应：一个字都不许写
    rows.value = normalize(data)
  } catch (e) {
    if (token !== loadGeneration) return
    // 失败时数据也是空的，如果只把 rows 清空，「请求挂了」和「这个资源确实没有数据」
    // 在界面上完全一样——排查时最误导的一种。所以另记一条错误，模板优先显示它。
    rows.value = []
    error.value = e?.message || '请求失败'
  } finally {
    if (token === loadGeneration) loading.value = false
  }
}

/**
 * 表头由数据推导：各资源字段不同，写死列在后端加字段时就看不到了。
 *
 * 例外是页签自己声明了 `columns`（目前只有「用户」）：那张表字段多，自动取前 8 个会把
 * 电话/邮箱挤掉，而这两列恰是该页最常看的——所以允许显式覆盖，但**默认仍是自动推导**。
 */
function pickColumns(tab, list) {
  const declared = tab?.columns
  if (declared?.length) {
    columns.value = declared.map((c) => ({ ...c, wide: c.wide ?? false }))
    return
  }
  if (!list.length) {
    columns.value = []
    return
  }
  const keys = Object.keys(list[0]).filter((k) => k !== 'deleted')
  columns.value = keys.slice(0, 8).map((k) => ({
    key: k,
    label: k,
    wide: typeof list[0][k] === 'string' && list[0][k].length > 24,
  }))
}

/** 当前页签的 fields 用到了哪些外键源 */
function neededRefs(tab) {
  return [...new Set(tab.fields.filter((f) => f.type === 'ref').map((f) => f.ref))]
}

async function ensureRefs(tab) {
  const missing = neededRefs(tab).filter((name) => !refOptions[name])
  if (!missing.length) return
  refLoading.value = true
  try {
    const results = await Promise.all(
      missing.map((name) => http.get(REFS[name].path).then((data) => normalize(data)).catch(() => [])),
    )
    missing.forEach((name, i) => {
      refOptions[name] = dedupeRef(name, results[i])
    })
  } finally {
    refLoading.value = false
  }
}

/** 外键源可选去重（见 REFS.metricCodes：同一个测项码在 /metrics 里每点一行） */
function dedupeRef(name, rows) {
  const key = REFS[name]?.dedupeBy
  if (!key) return rows
  const seen = new Set()
  return (rows || []).filter((r) => {
    const v = r?.[key]
    if (v == null || seen.has(v)) return false
    seen.add(v)
    return true
  })
}

/** 切页签和首次进入走同一条路：先取数，再按取到的数据推列 */
async function reload() {
  const tab = currentTab.value
  const token = ++loadGeneration
  await Promise.all([load(tab, token), ensureRefs(tab)])
  if (token !== loadGeneration) return
  pickColumns(tab, rows.value)
}

// ---------------------------------------------------------------------------
// 新建 / 编辑 / 删除
// ---------------------------------------------------------------------------

const dialogVisible = ref(false)
const editingId = ref(null)
const formRef = ref(null)
const form = ref({})

const dialogTitle = computed(() => (editingId.value ? '编辑' : '新建'))

/**
 * 表单实际要渲染的字段：`createOnly` 的字段（账号、初始密码）只在新建时出现。
 *
 * 为什么用 computed 而不是在 v-for 上写 v-if：Vue 3 里 v-if 的优先级**高于** v-for，
 * 同一个元素上写 `v-for + v-if="f.createOnly"` 时 `f` 还是未定义的，会直接报错。
 */
const visibleFields = computed(() =>
  (currentTab.value?.fields || []).filter((f) => !(f.createOnly && editingId.value)),
)

/** 表格单元格展示：布尔值给中文，空值给「—」（后端 null 直接渲染会是一片空白） */
function formatCell(key, value) {
  if (value === true) return key === 'enabled' ? '启用' : '是'
  if (value === false) return key === 'enabled' ? '停用' : '否'
  return value === null || value === undefined || value === '' ? '—' : value
}

/** 下拉选项的中文：角色与告警等级是枚举串，其余资源直接显示原值 */
function selectLabel(field, option) {
  if (field.key === 'role') return label(ROLE_LABELS, option)
  if (field.key === 'level') return label(LEVEL_LABELS, option)
  return option || '（不标注）'
}

/** 新建时按 fields 的 default 铺初值；不这么做，switch 会是 undefined（既不显示开也不显示关） */
function blankForm(tab) {
  const f = {}
  tab.fields.forEach((field) => {
    if (field.type === 'switch') f[field.key] = field.default ?? true
    else if (field.default !== undefined) f[field.key] = field.default
    else f[field.key] = field.type === 'number' ? null : ''
  })
  return f
}

/**
 * 编辑回填：只取 fields 里声明过的键。
 * 这样后端的 id / createdAt / deleted 不会被带进表单、也就不会在保存时被原样提交回去。
 */
function formFromRow(tab, row) {
  const f = blankForm(tab)
  tab.fields.forEach((field) => {
    if (row[field.key] !== undefined && row[field.key] !== null) f[field.key] = row[field.key]
  })
  return f
}

function openCreate() {
  editingId.value = null
  form.value = blankForm(currentTab.value)
  dialogVisible.value = true
}

function openEdit(row) {
  editingId.value = row.id
  form.value = formFromRow(currentTab.value, row)
  dialogVisible.value = true
}

/**
 * 提交前清洗：空串一律转 null。
 * 数字字段在 el-input 里清空后是 ''，直接发出去后端按 BigDecimal 解析会 400；
 * 而空串对「可空外键」的语义恰恰是「不设」——转 null 两个问题一起解决。
 */
function cleanPayload(tab) {
  const payload = {}
  tab.fields.forEach((field) => {
    // 只在新建时出现的字段（账号、初始密码）编辑时不提交：
    // 账号后端本来就不收，而把「初始密码」当普通字段回传会变成「改资料顺带改口令」。
    if (field.createOnly && editingId.value) return
    const v = form.value[field.key]
    if (field.type === 'number') payload[field.key] = v === '' || v === null || v === undefined ? null : Number(v)
    else if (field.type === 'ref') payload[field.key] = v === '' || v === null || v === undefined ? null : v
    else payload[field.key] = v === '' ? null : v
  })
  return payload
}

const saving = ref(false)

async function submit() {
  const tab = currentTab.value
  if (!(await formRef.value?.validate().catch(() => false))) return

  saving.value = true
  try {
    const payload = cleanPayload(tab)
    if (editingId.value) await http.put(`${tab.path}/${editingId.value}`, payload)
    else await http.post(tab.path, payload)
    ElMessage.success(editingId.value ? '已保存' : '已新建')
    dialogVisible.value = false
    // 外键源可能因这次写入而变化（比如刚新建了一个项目，场景下拉里就该有它）
    await Promise.all([reload(), ...neededRefs(tab).map(async (n) => {
      refOptions[n] = dedupeRef(n, normalize(await http.get(REFS[n].path)))
    })])
  } catch (e) {
    // 后端的校验信息就在这里（如「规则名称不能为空」），必须原样透出来
    ElMessage.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  const tab = currentTab.value
  const name = row.name || row.code || `#${row.id}`
  // 用户是唯一「删了会改变别人的使用」的资源，确认文案要把它说透：
  // 删除后该账号立刻登不上，本人需要用同一个账号名重新注册（后端会复用那行墓碑记录）。
  const message = tab.key === 'users'
    ? `确认删除账号「${row.username || name}」？删除后该账号立即失效、无法登录；`
      + '本人如需继续使用，得用同一个账号名重新注册（注册时重新填写个人信息）。'
    : `确认删除「${name}」？删除是逻辑删除（deleted=1），数据仍在库里可追溯。`
  try {
    await ElMessageBox.confirm(
      message,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return // 用户取消
  }
  try {
    await http.delete(`${tab.path}/${row.id}`)
    ElMessage.success('已删除')
    await reload()
  } catch (e) {
    ElMessage.error(e?.message || '删除失败')
  }
}

onMounted(reload)
</script>

<template>
  <div class="mk-panel admin-page">
    <div class="mk-panel-title">
      平台管理
      <span class="mk-muted title-sub">
        写操作需 ADMIN 角色；新建后业务端无需改代码即可见
      </span>
      <span class="mk-spacer" />
      <!-- 用户页没有「新建」：账号由本人自助注册（登录页 → 注册账号） -->
      <el-button
        v-if="!currentTab.noCreate"
        size="small"
        type="primary"
        :disabled="!!error"
        @click="openCreate"
      >新建</el-button>
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
        :formatter="(row, col, value) => formatCell(col.property, value)"
        show-overflow-tooltip
      />
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <!-- 有 error 就先说失败：空表和请求失败长得一样，不能都报「暂无数据」 -->
        <el-empty v-if="error" :description="`加载失败：${error}`" :image-size="60">
          <el-button size="small" type="primary" @click="reload">重试</el-button>
        </el-empty>
        <el-empty v-else description="该资源暂无数据" :image-size="60" />
      </template>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="`${dialogTitle}${currentTab.label}`" width="560px">
      <el-form ref="formRef" :model="form" label-width="110px" v-loading="refLoading">
        <el-form-item
          v-for="f in visibleFields"
          :key="f.key"
          :label="f.label"
          :prop="f.key"
          :rules="f.required ? [{ required: true, message: `${f.label}不能为空`, trigger: 'blur' }] : []"
        >
          <el-select
            v-if="f.type === 'ref'"
            v-model="form[f.key]"
            filterable
            clearable
            :placeholder="f.hint || `请选择${f.label}`"
            style="width: 100%"
          >
            <el-option
              v-for="opt in refOptions[f.ref] || []"
              :key="opt[REFS[f.ref].valueKey || 'id']"
              :label="REFS[f.ref].text(opt)"
              :value="opt[REFS[f.ref].valueKey || 'id']"
            />
          </el-select>

          <el-select
            v-else-if="f.type === 'select'"
            v-model="form[f.key]"
            style="width: 100%"
            :placeholder="f.hint || `请选择${f.label}`"
          >
            <el-option
              v-for="opt in f.options"
              :key="opt"
              :label="selectLabel(f, opt)"
              :value="opt"
            />
          </el-select>

          <el-switch v-else-if="f.type === 'switch'" v-model="form[f.key]" />

          <el-input
            v-else-if="f.type === 'password'"
            v-model="form[f.key]"
            type="password"
            show-password
            autocomplete="new-password"
            :placeholder="f.hint"
          />

          <el-input
            v-else-if="f.type === 'textarea'"
            v-model="form[f.key]"
            type="textarea"
            :rows="2"
          />

          <el-input v-else v-model="form[f.key]" :placeholder="f.hint" :type="f.type === 'number' ? 'number' : 'text'" />

          <div v-if="f.hint && f.type !== 'select' && f.type !== 'ref'" class="field-hint">{{ f.hint }}</div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
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

.field-hint {
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}
</style>
