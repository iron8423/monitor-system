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

/** pointId → 测点对象。绑定表本身不带点号，界面要显示就得自己 join 一次 */
const pointById = computed(() => Object.fromEntries(allPoints.value.map((p) => [p.id, p])))

/**
 * 已绑定的测点（补上点号/点名）。查不到的保留一个占位，
 * 不能直接过滤掉——那会让「绑了一个已删除的测点」在界面上彻底消失，
 * 用户看不到也就无从解绑。
 */
const boundRows = computed(() =>
  bound.value.map((b) => {
    const p = pointById.value[b.pointId]
    return {
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
            <el-table-column label="操作" width="90" align="right">
              <template #default="{ row }">
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
            绑定关系决定这台设备的<strong>归属</strong>：设备告警要经
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
</style>
