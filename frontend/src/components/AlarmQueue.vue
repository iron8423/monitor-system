<script setup>
import {
  ALARM_REASON_LABELS, ALARM_TYPE_LABELS, LEVEL_LABELS, LEVEL_TAG, STATUS_LABELS, STATUS_TAG, label,
} from '@/utils/labels'
// 队列列窄，用短格式（MM-DD HH:mm:ss）——但同样不能直接贴后端那串带纳秒的 ISO
import { formatTimeShort } from '@/utils/format'

/**
 * 紧凑警情队列表格。三个角色工作台共用同一张表，区别只在喂进来的 `status`：
 * 值班员看 PENDING、研判员看 CONFIRMED、运维员看 PROCESSING（映射见 `AlarmConstants`）。
 *
 * 与 `AlarmView` 的表格**不是**同一个东西，别合并：那张是告警中心的完整表，
 * 多一列状态、多一列操作，还要展开抽屉。这张只回答「我这一岗现在积了多少活」。
 *
 * 时间列直接显示后端给的原始 ISO（带 `+08:00`），与 `HomeAdmin` / `AlarmView` 同口径——
 * 换成 `MM-DD HH:mm:ss` 会丢掉时区信息，而时区正是这套接口反复强调的东西。
 */

defineOptions({ name: 'AlarmQueue' })

defineProps({
  alarms: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  /** 表格本身已按状态过滤时不必再显示状态列；管理员那页看的是全量，需要它 */
  showStatus: { type: Boolean, default: false },
  emptyText: { type: String, default: '暂无警情' },
})

const emit = defineEmits(['row-click'])
</script>

<template>
  <el-table
    :data="alarms"
    v-loading="loading"
    size="small"
    :show-header="false"
    class="queue"
    @row-click="(row) => emit('row-click', row)"
  >
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
        <!-- 设备告警的成因就地跟一行小字：同一台设备的「离线」「数据质量异常」是各一条，
             不写成因的话队列里两行完全一样，值班的看不出该找谁 -->
        <span v-if="row.alarmReason" class="mk-muted reason">{{ label(ALARM_REASON_LABELS, row.alarmReason) }}</span>
      </template>
    </el-table-column>
    <el-table-column v-if="showStatus" width="90">
      <template #default="{ row }">
        <el-tag :type="STATUS_TAG[row.status]" size="small">
          {{ label(STATUS_LABELS, row.status) }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column min-width="170">
      <template #default="{ row }">
        <span class="mk-mono mk-muted time">{{ formatTimeShort(row.triggeredAt) }}</span>
      </template>
    </el-table-column>
    <template #empty>
      <el-empty :description="emptyText" :image-size="50" />
    </template>
  </el-table>
</template>

<style scoped>
.time {
  font-size: 12px;
}

/* 设备告警成因：跟着对象名换行，小一号、弱化，不跟对象名抢位置 */
.reason {
  display: block;
  font-size: 12px;
  line-height: 1.2;
}

/* 行可点：点进去看详情 / 去告警中心处理。四处调用方都接了 row-click */
.queue :deep(.el-table__row) {
  cursor: pointer;
}
</style>
