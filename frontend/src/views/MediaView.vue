<script setup>
import { computed, onMounted, ref } from 'vue'

import * as api from '@/api/monitor'
import MediaGallery from '@/components/MediaGallery.vue'
import MediaUploader from '@/components/MediaUploader.vue'

/**
 * 影像挂点总览（阶段 5、验收第 6 条）。
 *
 * 与测点详情的「影像」页签是**同一个组件的两种排布**：那边看一个点，这边横着看所有点。
 * 所以这里只管「按测点分组 + 选一个点上传」，拉列表与拼地址仍走 {@link MediaGallery}——
 * 否则「token 要进 query」这条口径就有了第二份拷贝，迟早只改一处。
 *
 * 分组是按测点铺开而不是一次拉全部影像：接口就是按测点给的（`/points/{id}/media`），
 * 而且 7 个点的影像量级完全撑得住。点多了要改的话，改的是后端加一个跨点列表端点，
 * 不是在前端循环里发 N 个请求硬凑。
 */

defineOptions({ name: 'MediaView' })

const points = ref([])
const counts = ref({}) // pointId -> 张数，由各画廊 loaded 事件回填
const uploadTarget = ref(null)
const galleryRefs = ref({})

const totalLoaded = computed(() => Object.values(counts.value).reduce((a, b) => a + b, 0))

/** 没有影像的点排后面：演示时最想先看到「有图的那些」 */
const sortedPoints = computed(() =>
  [...points.value].sort((a, b) => (counts.value[b.id] || 0) - (counts.value[a.id] || 0)),
)

function onLoaded(pointId, items) {
  counts.value = { ...counts.value, [pointId]: items.length }
}

function reloadAll() {
  Object.values(galleryRefs.value).forEach((g) => g?.reload())
}

async function loadPoints() {
  try {
    points.value = (await api.listPoints()) || []
    if (points.value.length) uploadTarget.value = points.value[0].id
  } catch {
    points.value = []
  }
}

onMounted(loadPoints)
</script>

<template>
  <div class="media-page">
    <div class="mk-panel">
      <div class="mk-panel-title">
        影像挂点
        <span class="mk-muted title-sub">
          {{ points.length }} 个测点 · 已加载 {{ totalLoaded }} 张
        </span>
        <span class="mk-spacer" />
        <el-button size="small" link type="primary" @click="reloadAll">刷新</el-button>
      </div>

      <div class="toolbar">
        <span class="mk-muted label">上传到</span>
        <el-select v-model="uploadTarget" size="small" style="width: 220px" placeholder="选择测点">
          <el-option
            v-for="p in points"
            :key="p.id"
            :label="`${p.code} ${p.name || ''}`"
            :value="p.id"
          />
        </el-select>
        <MediaUploader :point-id="uploadTarget" @uploaded="reloadAll" />
        <span class="mk-spacer" />
        <span class="mk-muted count">缩略图点开可看大图、左右翻页；上传后本页自动刷新</span>
      </div>
    </div>

    <div v-for="p in sortedPoints" :key="p.id" class="mk-panel point-block">
      <div class="mk-panel-title">
        <span class="mk-mono">{{ p.code }}</span>
        <span class="point-name">{{ p.name }}</span>
        <span class="mk-muted title-sub">{{ counts[p.id] ?? 0 }} 张</span>
        <span class="mk-spacer" />
        <el-button size="small" link type="primary" @click="uploadTarget = p.id">
          上传到此点
        </el-button>
      </div>
      <MediaGallery
        :ref="(el) => (galleryRefs[p.id] = el)"
        :point-id="p.id"
        :columns="6"
        size="88px"
        deletable
        @loaded="(items) => onLoaded(p.id, items)"
      />
    </div>

    <el-empty v-if="!points.length" description="没有测点" />
  </div>
</template>

<style scoped>
.media-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
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
  flex-wrap: wrap;
  padding: 12px 16px;
  border-bottom: 1px solid var(--mk-border);
}

.label {
  font-size: 13px;
}

.count {
  font-size: 12px;
}

.point-block :deep(.el-empty) {
  padding: 8px 0;
}

.point-name {
  margin-left: 8px;
  font-size: 12px;
  font-weight: 400;
  color: var(--mk-text-sub);
}
</style>
