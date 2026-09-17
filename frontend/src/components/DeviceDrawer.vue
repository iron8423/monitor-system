<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import * as api from '@/api/monitor'
import { useUserStore } from '@/stores/user'
import { DEVICE_STATUS_LABELS, DEVICE_STATUS_TAG, label } from '@/utils/labels'

/**
 * 设备详情抽屉：基本信息 / 绑定测点 / 维护记录。
 *
 * **为什么抽成组件**：设备页与运维台都要开它（运维台的动线是「看到设备异常 → 点行 →
 * 就地处理」）。两处各写一份的话，绑定与维护这两块的口径迟早分叉——这正是
 * `MediaGallery` 当初被抽出来的同一条理由。
 *
 * 抽屉自己拉数据（`device` 一变就重拉），父组件只负责「开谁」。
 * 这样两处调用点都不必各自维护三份 loading / 三份错误处理。
 */

defineOptions({ name: 'DeviceDrawer' })

const props = defineProps({
  /** 要展示的设备；null = 关闭。父组件用 `v-model:device` 或直接绑一个 ref */
  device: { type: Object, default: null },
})

const emit = defineEmits(['update:device'])

const userStore = useUserStore()

/**
 * 可写闸：绑定/解绑与维护记录的写接口都是 ADMIN/MAINTAINER（后端 `@PreAuthorize`）。
 * 值班/研判打开抽屉时**只读**——这里只是不显示按钮，不是权限。
 */
const canWrite = computed(() => ['ADMIN', 'MAINTAINER'].includes(userStore.role))

const visible = computed({
  get: () => !!props.device,
  set: (v) => !v && emit('update:device', null),
})

const tab = ref('info')

// ---------- 绑定测点 ----------
const bound = ref([]) // DevicePoint[]：只有 pointId
const allPoints = ref([]) // MonitorPoint[]：用来把 pointId 翻成点号/点名
const bindLoading = ref(false)
const binding = ref(false)
const pickedPointId = ref(null)
const calibrationVisible = ref(false)
const calibrationSaving = ref(false)
const calibrationRow = ref(null)
const calibrationForm = ref({})

/** pointId → 测点对象。绑定表本身不带点号，界面要显示就得自己 join 一次 */
const pointById = computed(() => Object.fromEntries(allPoints.value.map((p) => [p.id, p])))

/**
 * 安装几何的只读展示。
 * **空值必须显示成「—」而不是 0**：「没维护过」与「填了 0」在这几个字段上是两件事，
 * 0 度朝向是一个合法取值。有值则裁掉浮点尾巴（后端是 BigDecimal，回传可能带一长串小数）。
 */
function fmtGeo(v, unit) {
  if (v === null || v === undefined || v === '') return '—'
  const n = Number(v)
  return `${Number.isFinite(n) ? Number(n.toFixed(3)) : v}${unit}`
}

/**
 * 标定状态 → 标签（**五态**；此前这里写的是「四态」，与下面的分支数对不上——少了「未生效」）。
 *
 * **为什么不能还是两态**：后端在设备被移动/转向、或测点被移动后会把标定置为
 * `INVALID`（清单第 09 条）。旧的二选一写法（`ACTIVE && lineOfSight ? 有效 : 待标定`）
 * 会把「已失效」显示成「**待标定**」——而这两件事对运维的结论完全不同：
 * 一个是从来没标定过，一个是**标过、现在不能信**。后者还要追一句「为什么失效了」，
 * 所以 tooltip 里带上原因与时刻。
 *
 * 五态与后端 `RadarCoveragePolicy` 一一对应：
 *   - `INVALID` → 已失效（曾经标定过，几何前提已不成立 / 人工停用）
 *   - `ACTIVE` + 视线通 + 在有效期内 → 有效
 *   - `ACTIVE` + 视线通 + **还没到 `validFrom`** → 未生效。这一档在补上有效期输入
 *     （清单第 16 条，下面那个对话框）之前是**点不出来的**——界面上根本填不出未来的
 *     `validFrom`。所以它此前一直像死代码，现在才是活的
 *   - `ACTIVE` + 视线通但有效期过了 → 已过期（补上抽屉此前完全忽略 `validFrom`/`validTo` 的缺口）
 *   - 其余（`ACTIVE` 但视线不通、或 `PENDING`）→ 待标定
 */
const CALIBRATION_REASON_LABELS = {
  DEVICE_POSE_CHANGED: '设备位置或朝向变更',
  POINT_MOVED: '测点位置变更',
  MANUAL: '人工停用',
  DEVICE_RELOCATED: '设备搬迁',
  TARGET_REMOVED: '目标已移除',
  MAINTENANCE: '检修',
  SUSPECTED_DRIFT: '疑似漂移',
}

function calibrationTag(row) {
  if (row.calibrationStatus === 'INVALID') {
    const why = CALIBRATION_REASON_LABELS[row.invalidatedReason] || row.invalidatedReason || '原因未记录'
    const when = row.invalidatedAt ? `，${String(row.invalidatedAt).replace('T', ' ')}` : ''
    const who = row.invalidatedBy ? `，操作人 ${row.invalidatedBy}` : ''
    return { text: '已失效', type: 'danger', tip: `${why}${when}${who}。需要重新标定，旧标定按旧位置量出，不能再信。` }
  }
  if (row.calibrationStatus !== 'ACTIVE' || !row.lineOfSight) {
    return { text: '待标定', type: 'warning', tip: '尚未标定，不能接收生产数据。' }
  }
  const now = Date.now()
  const from = row.validFrom ? Date.parse(row.validFrom) : null
  const to = row.validTo ? Date.parse(row.validTo) : null
  if (from != null && now < from) {
    return { text: '未生效', type: 'info', tip: `有效期自 ${row.validFrom} 起，当前尚未生效。` }
  }
  if (to != null && now > to) {
    return { text: '已过期', type: 'warning', tip: `有效期已于 ${row.validTo} 结束，需要重新标定。` }
  }
  return { text: '有效', type: 'success', tip: row.validTo ? `有效期至 ${row.validTo}。` : '长期有效。' }
}

/**
 * 已绑定的测点（补上点号/点名）。查不到的保留一个占位，
 * 不能直接过滤掉——那会让「绑了一个已删除的测点」在界面上彻底消失，
 * 用户看不到也就无从解绑。
 */
const boundRows = computed(() =>
  bound.value.map((b) => {
    const p = pointById.value[b.pointId]
    return {
      ...b,
      pointId: b.pointId,
      code: p?.code || `#${b.pointId}`,
      name: p?.name || '（测点档案已不存在）',
      missing: !p,
    }
  }),
)

/** 可选的测点 = 全部 - 已绑定。空的话下拉就没得选，别让用户点开才发现 */
const selectablePoints = computed(() => {
  const taken = new Set(bound.value.map((b) => b.pointId))
  return allPoints.value.filter((p) => !taken.has(p.id))
})

function openCalibration(row) {
  calibrationRow.value = row
  calibrationForm.value = {
    targetCode: row.targetCode || `${props.device.code}-${row.code}`,
    azimuthDegrees: row.azimuthDegrees ?? props.device.headingDegrees ?? 0,
    elevationDegrees: row.elevationDegrees ?? props.device.pitchDegrees ?? 0,
    slantRangeM: row.slantRangeM ?? 1,
    reflectorHeightM: row.reflectorHeightM ?? 2.5,
    lineOfSight: row.lineOfSight ?? true,
    minimumClearanceM: row.minimumClearanceM ?? null,
    validFrom: row.validFrom ?? null,
    validTo: row.validTo ?? null,
    note: row.calibrationNote || '',
  }
  calibrationVisible.value = true
}

async function saveCalibration() {
  const row = calibrationRow.value
  if (!row) return
  calibrationSaving.value = true
  try {
    await api.calibrateDevicePoint(props.device.id, row.pointId, calibrationForm.value)
    ElMessage.success('标定已激活')
    calibrationVisible.value = false
    await loadBindings()
  } catch {
    /* 拦截器已提示具体的量程/FOV/遮挡原因 */
  } finally {
    calibrationSaving.value = false
  }
}

// ---------- 维护记录 ----------
const records = ref([])
const recLoading = ref(false)
const recForm = ref({ type: '', description: '' })
const recSaving = ref(false)

/**
 * 维护类型是**建议值不是枚举**：库里 `maintenance_record.type` 是 VARCHAR(32) 自由文本，
 * 后端不做校验。所以下拉用 `allow-create` 允许自己填，别把这几个值当成契约——
 * 它们只是让运维少打几个字。
 */
const RECORD_TYPES = ['日常巡检', '清洁保养', '更换电池', '固件升级', '故障维修', '更换设备', '其他']

async function loadBindings() {
  const id = props.device?.id
  if (!id) return
  bindLoading.value = true
  try {
    // 两个请求互相独立，并行发；都成功才一起赋值，避免出现「绑定表更新了点表没更新」的中间态
    const [b, p] = await Promise.all([api.listDevicePoints(id), api.listPoints()])
    bound.value = b || []
    allPoints.value = p || []
  } catch {
    bound.value = []
  } finally {
    bindLoading.value = false
  }
}

async function loadRecords() {
  const id = props.device?.id
  if (!id) return
  recLoading.value = true
  try {
    records.value = (await api.listMaintenanceRecords(id)) || []
  } catch {
    records.value = []
  } finally {
    recLoading.value = false
  }
}

function loadAll() {
  loadBindings()
  loadRecords()
}

async function doBind() {
  if (!pickedPointId.value) return ElMessage.warning('先选一个测点')
  binding.value = true
  try {
    await api.bindDevicePoint(props.device.id, pickedPointId.value)
    ElMessage.success('已绑定')
    pickedPointId.value = null
    await loadBindings()
  } catch {
    // 已绑定 / 403 等由 http 拦截器提示，这里不重复弹
  } finally {
    binding.value = false
  }
}

async function doUnbind(row) {
  try {
    await ElMessageBox.confirm(
      `解除后该设备的离线判定不再覆盖「${row.code}」，但测点本身、以及它已有的数据都不受影响。`,
      `解绑「${row.code}」`,
      { type: 'warning', confirmButtonText: '解绑', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await api.unbindDevicePoint(props.device.id, row.pointId)
    ElMessage.success('已解绑')
    await loadBindings()
  } catch {
    /* 拦截器已提示 */
  }
}

// ---------- 停用标定（清单第 16 条） ----------

/**
 * 「停用」与「解绑」是两件事，界面上必须分得开（`api/monitor.js:154-168` 的注释
 * 点名的就是这个坑：两个端点只差一段路径、破坏性却相反）：
 *   - 停用 = 保留绑定关系与全部标定参数（方位角/斜距/视线），只把状态置 `INVALID`，
 *     重新标定即可恢复；
 *   - 解绑 = **硬删行**，那些参数一起丢，且要重新绑定。
 * 所以停用用 warning、解绑用 danger，文案也各说各的后果。
 */
const invalidateVisible = ref(false)
const invalidateRow = ref(null)
const invalidateReason = ref('MANUAL')
const invalidateSaving = ref(false)

/**
 * 原因走**后端白名单**（传别的 400），所以只能是下拉、不能是自由文本。
 * 也因此**不用 `ElMessageBox.prompt`**：它的 `inputType` 不支持 select。
 */
const INVALIDATE_REASONS = [
  'MANUAL',
  'DEVICE_RELOCATED',
  'TARGET_REMOVED',
  'MAINTENANCE',
  'SUSPECTED_DRIFT',
]

function openInvalidate(row) {
  invalidateRow.value = row
  invalidateReason.value = 'MANUAL'
  invalidateVisible.value = true
}

async function doInvalidate() {
  const row = invalidateRow.value
  if (!row) return
  invalidateSaving.value = true
  try {
    await api.invalidateDevicePointCalibration(props.device.id, row.pointId, invalidateReason.value)
    ElMessage.success('标定已停用，绑定关系与标定参数都还在')
    invalidateVisible.value = false
    await loadBindings()
  } catch {
    /* 拦截器已提示（403 / 400 白名单） */
  } finally {
    invalidateSaving.value = false
  }
}

async function submitRecord() {
  if (!recForm.value.description.trim()) return ElMessage.warning('维护说明不能为空')
  recSaving.value = true
  try {
    await api.createMaintenanceRecord({
      deviceId: props.device.id,
      type: recForm.value.type || null,
      description: recForm.value.description.trim(),
    })
    ElMessage.success('已记录')
    recForm.value = { type: '', description: '' }
    await loadRecords()
  } catch {
    /* 拦截器已提示 */
  } finally {
    recSaving.value = false
  }
}

// 换设备必须三块一起重拉，并回到第一页签——
// 留在「维护记录」页签看另一台设备的记录，会让人以为看的是同一台
watch(
  () => props.device?.id,
  (id) => {
    if (!id) return
    tab.value = 'info'
    pickedPointId.value = null
    recForm.value = { type: '', description: '' }
    loadAll()
  },
  { immediate: true },
)
</script>

<template>
  <el-drawer
    v-model="visible"
    :title="device ? `${device.name || device.code}` : ''"
    size="620px"
    destroy-on-close
  >
    <template #header>
      <div class="drawer-head">
        <span class="code mk-mono">{{ device?.code }}</span>
        <span class="name">{{ device?.name || '未命名设备' }}</span>
        <el-tag v-if="device" :type="DEVICE_STATUS_TAG[device.status] || 'info'" size="small">
          {{ label(DEVICE_STATUS_LABELS, device.status) }}
        </el-tag>
      </div>
    </template>

    <el-tabs v-model="tab">
      <!-- ① 基本信息 -->
      <el-tab-pane label="基本信息" name="info">
        <el-descriptions v-if="device" :column="1" border size="small">
          <el-descriptions-item label="设备编号">
            <span class="mk-mono">{{ device.code }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="名称">{{ device.name || '—' }}</el-descriptions-item>
          <el-descriptions-item label="类型">{{ device.type || '—' }}</el-descriptions-item>
          <el-descriptions-item label="序列号">
            <span class="mk-mono">{{ device.serialNo || '—' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="经纬度">
            <span class="mk-mono">
              {{ device.longitude ?? '—' }}, {{ device.latitude ?? '—' }}
            </span>
          </el-descriptions-item>
          <!--
            安装几何（清单第 14 条）。这 7 个字段此前只存在于管理端的**测点**页签里
            （提交给 /v1/points，被 Jackson 静默丢弃），界面上无处可看。
            在这里只读展示的意义是：改完能立刻核对「到底改成什么了」——
            它们决定标定的空间判据，改错一个整台雷达的标定就作废（第 09 条）。
          -->
          <el-descriptions-item label="安装几何">
            <div class="geo-grid mk-mono">
              <span>基座高程 {{ fmtGeo(device.altitude, 'm') }}</span>
              <span>朝向角 {{ fmtGeo(device.headingDegrees, '°') }}</span>
              <span>俯仰角 {{ fmtGeo(device.pitchDegrees, '°') }}</span>
              <span>探测量程 {{ fmtGeo(device.detectionRangeM, 'm') }}</span>
              <span>水平半视场 {{ fmtGeo(device.halfAngleDegrees, '°') }}</span>
              <span>垂直半视场 {{ fmtGeo(device.verticalHalfAngleDegrees, '°') }}</span>
              <span>天线离地 {{ fmtGeo(device.antennaHeightM, 'm') }}</span>
            </div>
            <span class="mk-muted hint">改动任一项都会使已生效的标定失效，需重新标定</span>
          </el-descriptions-item>
          <el-descriptions-item label="电量">
            <span class="mk-mono">
              {{ device.battery != null ? `${device.battery}%` : '—' }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="最后上报">
            <span class="mk-mono">{{ device.lastReportTime || '从未上报' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="DEVICE_STATUS_TAG[device.status] || 'info'" size="small">
              {{ label(DEVICE_STATUS_LABELS, device.status) }}
            </el-tag>
            <span class="mk-muted hint">
              由后端按「5 分钟未上报」推导，不是档案里存的字段
            </span>
          </el-descriptions-item>
        </el-descriptions>
      </el-tab-pane>

      <!-- ② 绑定测点 -->
      <el-tab-pane name="points">
        <template #label>
          绑定测点
          <span v-if="bound.length" class="mk-muted tab-n">{{ bound.length }}</span>
        </template>

        <div v-loading="bindLoading">
          <div v-if="canWrite" class="bind-bar">
            <el-select
              v-model="pickedPointId"
              filterable
              clearable
              placeholder="选择要绑定的测点"
              class="picker"
            >
              <el-option
                v-for="p in selectablePoints"
                :key="p.id"
                :label="`${p.code} · ${p.name}`"
                :value="p.id"
              />
            </el-select>
            <el-button type="primary" :loading="binding" @click="doBind">绑定</el-button>
          </div>
          <el-alert
            v-else
            type="info"
            :closable="false"
            show-icon
            title="绑定/解绑限管理员与运维员"
            description="值班与研判可以查看绑定关系，但不能改。这是后端 @PreAuthorize 定的，不是界面挡住。"
          />

          <el-table :data="boundRows" size="small" class="bind-table">
            <el-table-column label="点号" width="140">
              <template #default="{ row }">
                <span class="mk-mono">{{ row.code }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="name" label="测点名称" min-width="150" />
            <el-table-column label="标定" width="95">
              <template #default="{ row }">
                <el-tooltip
                  :content="calibrationTag(row).tip"
                  placement="top"
                  :show-after="200"
                >
                  <el-tag size="small" :type="calibrationTag(row).type">
                    {{ calibrationTag(row).text }}
                  </el-tag>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="斜距" width="80">
              <template #default="{ row }">
                <span class="mk-mono">{{ row.slantRangeM != null ? `${row.slantRangeM}m` : '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="190" align="right">
              <template #default="{ row }">
                <el-button
                  v-if="canWrite"
                  link
                  type="primary"
                  size="small"
                  @click="openCalibration(row)"
                >
                  标定
                </el-button>
                <!--
                  停用只有已标定过的行才有意义（PENDING 的行「停用」等于什么也没说）。
                  放在标定与解绑中间：破坏性居中，误点的代价最小
                -->
                <el-button
                  v-if="canWrite && row.calibrationStatus === 'ACTIVE'"
                  link
                  type="warning"
                  size="small"
                  @click="openInvalidate(row)"
                >
                  停用
                </el-button>
                <el-button
                  v-if="canWrite"
                  link
                  type="danger"
                  size="small"
                  @click="doUnbind(row)"
                >
                  解绑
                </el-button>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty :image-size="50" description="该设备还没绑定任何测点" />
            </template>
          </el-table>

          <div class="mk-footnote">
            新绑定默认为<strong>待标定</strong>，不能接收生产数据。完成量程、水平/垂直视场和
            地形视线校验后才会激活。绑定关系同时决定这台设备的<strong>归属</strong>：设备告警要经
            <span class="mk-mono">device_point</span> 反查测点才能算进项目概览的「未解除警情」。
            <strong>一台设备绑了多个测点时，它的告警只算一次</strong>——按测点去重，不是按绑定条数。
            未绑定任何测点的设备<strong>没有项目归属</strong>。
          </div>
        </div>
      </el-tab-pane>

      <!-- ③ 维护记录 -->
      <el-tab-pane name="records">
        <template #label>
          维护记录
          <span v-if="records.length" class="mk-muted tab-n">{{ records.length }}</span>
        </template>

        <div v-if="canWrite" class="rec-form">
          <el-select
            v-model="recForm.type"
            filterable
            allow-create
            clearable
            placeholder="类型（可自填）"
            class="rec-type"
          >
            <el-option v-for="t in RECORD_TYPES" :key="t" :label="t" :value="t" />
          </el-select>
          <el-input
            v-model="recForm.description"
            type="textarea"
            :rows="2"
            maxlength="512"
            show-word-limit
            placeholder="这次做了什么（例：更换电池，恢复上报）"
          />
          <div class="rec-actions">
            <el-button type="primary" :loading="recSaving" @click="submitRecord">
              记录一次维护
            </el-button>
          </div>
        </div>
        <el-alert
          v-else
          type="info"
          :closable="false"
          show-icon
          title="新增维护记录限管理员与运维员"
          description="值班与研判可以查看历史维护记录。"
        />

        <div v-loading="recLoading" class="rec-list">
          <el-timeline v-if="records.length">
            <el-timeline-item
              v-for="r in records"
              :key="r.id"
              :timestamp="r.createdAt"
              placement="top"
            >
              <div class="rec-item">
                <el-tag v-if="r.type" size="small" type="info">{{ r.type }}</el-tag>
                <span class="rec-desc">{{ r.description }}</span>
                <span class="mk-muted rec-op">— {{ r.operator || '未知' }}</span>
              </div>
            </el-timeline-item>
          </el-timeline>
          <el-empty v-else-if="!recLoading" :image-size="50" description="还没有维护记录" />
        </div>

        <div class="mk-footnote">
          记录人由后端从当前登录用户填，客户端传什么都不作数——
          「谁写的」不该由前端说了算。类型是自由文本，下拉里的几个值只是省打字，不是枚举。
        </div>
      </el-tab-pane>
    </el-tabs>
  </el-drawer>

  <el-dialog v-model="calibrationVisible" title="雷达目标标定" width="560px" append-to-body>
    <el-form label-width="138px" size="small">
      <el-form-item label="测点">
        <span class="mk-mono">{{ calibrationRow?.code }} · {{ calibrationRow?.name }}</span>
      </el-form-item>
      <el-form-item label="目标编号" required>
        <el-input v-model="calibrationForm.targetCode" />
      </el-form-item>
      <el-form-item label="绝对方位角（°）" required>
        <el-input-number v-model="calibrationForm.azimuthDegrees" :min="0" :max="360" :precision="3" />
      </el-form-item>
      <el-form-item label="目标俯仰角（°）" required>
        <el-input-number v-model="calibrationForm.elevationDegrees" :min="-90" :max="90" :precision="3" />
      </el-form-item>
      <el-form-item label="目标斜距（m）" required>
        <el-input-number v-model="calibrationForm.slantRangeM" :min="0.001" :precision="3" />
      </el-form-item>
      <el-form-item label="反射器高度（m）">
        <el-input-number v-model="calibrationForm.reflectorHeightM" :min="0" :precision="3" />
      </el-form-item>
      <el-form-item label="地形视线">
        <el-switch v-model="calibrationForm.lineOfSight" active-text="无遮挡" inactive-text="被遮挡" />
      </el-form-item>
      <el-form-item label="最小净空（m）">
        <el-input-number v-model="calibrationForm.minimumClearanceM" :precision="3" />
      </el-form-item>
      <!--
        有效期（清单第 16 条）。**这个对话框以前没有这两项**，而 openCalibration 会把
        行里已有的 validFrom/validTo 带进表单、saveCalibration 把整个对象发出去——
        于是「从界面重新标定」会**不知不觉沿用上一次的有效期**：上一轮填的是「年底到期」，
        这一轮还是年底到期，哪怕你根本没打算设。既然有效期是标定的一部分，就必须看得见、改得动。
        两项都可留空，留空 = 不限（不是「立即失效」）。
      -->
      <el-form-item label="有效期自">
        <el-date-picker
          v-model="calibrationForm.validFrom"
          type="datetime"
          value-format="YYYY-MM-DDTHH:mm:ss"
          placeholder="留空 = 立即生效"
          clearable
        />
      </el-form-item>
      <el-form-item label="有效期至">
        <el-date-picker
          v-model="calibrationForm.validTo"
          type="datetime"
          value-format="YYYY-MM-DDTHH:mm:ss"
          placeholder="留空 = 长期有效"
          clearable
        />
        <span class="mk-muted hint">两项都留空即长期有效，不做时间限制</span>
      </el-form-item>
      <el-form-item label="标定说明">
        <el-input v-model="calibrationForm.note" type="textarea" :rows="2" maxlength="512" show-word-limit />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="calibrationVisible = false">取消</el-button>
      <el-button type="primary" :loading="calibrationSaving" @click="saveCalibration">校验并激活</el-button>
    </template>
  </el-dialog>

  <!-- 停用标定：确认框 + 原因下拉（原因必须是后端白名单里的一个，所以不是自由文本） -->
  <el-dialog v-model="invalidateVisible" title="停用标定" width="460px" append-to-body>
    <p class="invalidate-lead">
      将把
      <span class="mk-mono">{{ invalidateRow?.code }}</span>
      的标定置为<strong>已失效</strong>，该测点随即<strong>不再接收生产数据</strong>。
    </p>
    <el-form label-width="88px" size="small">
      <el-form-item label="停用原因">
        <el-select v-model="invalidateReason" style="width: 100%">
          <el-option
            v-for="r in INVALIDATE_REASONS"
            :key="r"
            :label="CALIBRATION_REASON_LABELS[r] || r"
            :value="r"
          />
        </el-select>
      </el-form-item>
    </el-form>
    <div class="mk-footnote">
      保留绑定关系与标定参数（方位角/斜距/视线），重新标定即可恢复。
      要<strong>彻底解除</strong>这台设备与该测点的关系，请用表格里的「解绑」——
      那个会连同这些参数一起删掉。
    </div>
    <template #footer>
      <el-button @click="invalidateVisible = false">取消</el-button>
      <el-button type="warning" :loading="invalidateSaving" @click="doInvalidate">确认停用</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.drawer-head {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.drawer-head .code {
  font-size: 13px;
  color: var(--mk-text-sub);
}

.drawer-head .name {
  font-size: 16px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hint {
  margin-left: 8px;
  font-size: 12px;
}

.tab-n {
  margin-left: 4px;
  font-size: 11px;
}

.bind-bar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.picker {
  flex: 1;
  min-width: 0;
}

.bind-table {
  margin-top: 4px;
}

.rec-form {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 16px;
}

.rec-type {
  width: 100%;
}

.rec-actions {
  display: flex;
  justify-content: flex-end;
}

.rec-list {
  min-height: 60px;
}

.rec-item {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}

.rec-desc {
  word-break: break-word;
}

.rec-op {
  font-size: 12px;
}

/* 安装几何：7 项在一格里折行排，别撑破 el-descriptions 的单列宽度 */
.geo-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  font-size: 12px;
}

.invalidate-lead {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.6;
}
</style>
