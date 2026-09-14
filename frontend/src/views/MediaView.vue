<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'

import * as api from '@/api/monitor'
import { useUserStore } from '@/stores/user'
import { formatTime } from '@/utils/format'

/**
 * 影像挂点（阶段 5，对应验收脚本第 6 条「无人机照片挂测点」）。
 *
 * 契约 §7：上传是 multipart（file/pointId/takenAt/note），列表是**裸数组**，
 * 图片本体走 `/media/{mediaId}/content`——`<img>` 带不了请求头，所以那里用 `?token=`。
 * mediaId 是**不透明字符串**（`M1000`），前端只当它是 id，不解析数字部分。
 */

defineOptions({ name: 'MediaView' })

const route = useRoute()
const userStore = useUserStore()

const points = ref([])
const selectedId = ref(null)
const photos = ref([])
const loading = ref(false)

const pickedFile = ref(null)
const pickedPreview = ref('')
const takenAt = ref('')
const note = ref('')
const uploading = ref(false)

const currentPoint = computed(() => points.value.find((p) => p.id === selectedId.value) || null)

/** 图片地址带 token：这是契约给 `<img>` 场景开的口子（与 SSE 的 ?token= 同一类） */
const contentUrl = (mediaId) => api.mediaContentUrl(mediaId, userStore.token)
const previewList = computed(() => photos.value.map((m) => contentUrl(m.mediaId)))

async function loadPoints() {
  try {
    points.value = (await api.listPoints()) || []
  } catch {
    points.value = []
  }
  // 支持深链 /media?pointId=3（大屏弹窗、测点页都从这儿跳过来）
  const fromQuery = Number(route.query.pointId)
  if (Number.isFinite(fromQuery) && points.value.some((p) => p.id === fromQuery)) {
    selectedId.value = fromQuery
  } else if (points.value.length && !selectedId.value) {
    selectedId.value = points.value[0].id
  }
}

async function loadPhotos() {
  if (!selectedId.value) {
    photos.value = []
    return
  }
  loading.value = true
  try {
    photos.value = (await api.pointMedia(selectedId.value)) || []
  } catch {
    photos.value = []
  } finally {
    loading.value = false
  }
}

/**
 * 选中文件。这里用 `:auto-upload="false"` + `on-change`：Element Plus 的自动上传
 * 会自己发一次请求（还得再配 http-request 才能改字段），不如把「选」和「传」分开，
 * 让人先填拍摄时间/备注再提交——顺带避免选错文件就直接传上去。
 */
function onPick(uploadFile) {
  const file = uploadFile?.raw
  if (!file) return
  if (!file.type?.startsWith('image/')) {
    ElMessage.warning('只能上传图片（jpg / png / webp / gif / bmp）')
    return
  }
  if (pickedPreview.value) URL.revokeObjectURL(pickedPreview.value)
  pickedFile.value = file
  pickedPreview.value = URL.createObjectURL(file)
}

function clearForm() {
  if (pickedPreview.value) URL.revokeObjectURL(pickedPreview.value)
  pickedFile.value = null
  pickedPreview.value = ''
  takenAt.value = ''
  note.value = ''
}

async function doUpload() {
  if (!pickedFile.value || !selectedId.value) return
  uploading.value = true
  try {
    const vo = await api.uploadMedia(pickedFile.value, {
      pointId: selectedId.value,
      // 空串会让后端解析失败（Times.parse 对非法串是 400），所以不填就不传这个字段
      takenAt: takenAt.value || undefined,
      note: note.value.trim() || undefined,
    })
    ElMessage.success(`已挂到 ${currentPoint.value?.code || '该测点'}：${vo.mediaId}`)
    clearForm()
    await loadPhotos()
  } catch {
    // 拦截器已统一弹错，这里只需要把 loading 收掉（finally 做）
  } finally {
    uploading.value = false
  }
}

onMounted(async () => {
  await loadPoints()
  await loadPhotos()
})

watch(selectedId, loadPhotos)
onBeforeUnmount(() => {
  if (pickedPreview.value) URL.revokeObjectURL(pickedPreview.value)
})
</script>

<template>
  <div class="media-page">
    <div class="mk-panel point-panel">
      <div class="mk-panel-title">
        测点
        <span class="mk-muted title-sub">{{ points.length }}</span>
      </div>
      <div class="point-list">
        <div
          v-for="p in points"
          :key="p.id"
          class="point-item"
          :class="{ active: p.id === selectedId }"
          @click="selectedId = p.id"
        >
          <span class="mk-mono point-code">{{ p.code }}</span>
          <span class="point-name">{{ p.name }}</span>
        </div>
        <el-empty v-if="!points.length" description="没有测点" :image-size="60" />
      </div>
    </div>

    <div class="right-col">
      <div class="mk-panel">
        <div class="mk-panel-title">
          上传现场照片
          <span v-if="currentPoint" class="mk-muted title-sub">
            将挂到 {{ currentPoint.code }} · {{ currentPoint.name }}
          </span>
          <span class="mk-spacer" />
          <el-button size="small" link type="primary" @click="loadPhotos">刷新</el-button>
        </div>

        <div class="upload-bar">
          <el-upload
            :auto-upload="false"
            :show-file-list="false"
            accept="image/*"
            :on-change="onPick"
          >
            <el-button>选择图片</el-button>
          </el-upload>

          <div v-if="pickedPreview" class="picked">
            <img :src="pickedPreview" alt="待上传预览" />
            <span class="mk-muted picked-name">{{ pickedFile?.name }}</span>
          </div>
          <span v-else class="mk-muted">支持 jpg / png / webp / gif / bmp；文件名不会落盘</span>

          <span class="mk-spacer" />

          <el-date-picker
            v-model="takenAt"
            type="datetime"
            size="small"
            placeholder="拍摄时间（默认现在）"
            value-format="YYYY-MM-DDTHH:mm:ssZ"
            style="width: 210px"
          />
          <el-input
            v-model="note"
            size="small"
            placeholder="备注：巡检位置 / 说明"
            style="width: 220px"
          />
          <el-button
            type="primary"
            size="small"
            :loading="uploading"
            :disabled="!pickedFile"
            @click="doUpload"
          >
            上传并挂点
          </el-button>
        </div>

        <div v-loading="loading" class="photo-body">
          <div v-if="photos.length" class="photo-grid">
            <figure v-for="(m, i) in photos" :key="m.mediaId" class="photo-card">
              <el-image
                :src="contentUrl(m.mediaId)"
                :preview-src-list="previewList"
                :initial-index="i"
                fit="cover"
                class="photo"
                preview-teleported
              />
              <figcaption>
                <div class="meta-line">
                  <span class="mk-mono">{{ m.mediaId }}</span>
                  <span class="mk-muted">{{ formatTime(m.takenAt) }}</span>
                </div>
                <div v-if="m.note" class="note">{{ m.note }}</div>
              </figcaption>
            </figure>
          </div>
          <el-empty
            v-else-if="!loading"
            description="这个测点还没有现场照片，选一张上传试试"
            :image-size="80"
          />
        </div>

        <div class="mk-footnote">
          影像按<strong>测点</strong>挂点（验收第 6 条：无人机照片挂到测点 → 详情页能看）。
          `mediaId` 是不透明编码，前端不解析它的数字部分；图片本体走
          <span class="mk-mono">/api/v1/media/{mediaId}/content?token=</span>，
          因为 <span class="mk-mono">&lt;img&gt;</span> 带不了 Authorization 头。
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.media-page {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.point-panel {
  width: 220px;
  flex-shrink: 0;
}

.point-list {
  padding: 6px;
}

.point-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  border-radius: 4px;
  cursor: pointer;
  transition: background 0.15s;
}

.point-item:hover {
  background: var(--mk-bg);
}

.point-item.active {
  background: rgba(31, 111, 235, 0.08);
}

.point-item.active .point-code {
  color: var(--mk-primary);
}

.point-code {
  font-size: 13px;
}

.point-name {
  font-size: 12px;
  color: var(--mk-text-sub);
}

.right-col {
  flex: 1;
  min-width: 0;
}

.title-sub {
  margin-left: 10px;
  font-size: 12px;
  font-weight: 400;
}

.upload-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 10px 16px;
  border-bottom: 1px solid var(--mk-border);
}

.picked {
  display: flex;
  gap: 8px;
  align-items: center;
}

.picked img {
  width: 34px;
  height: 34px;
  object-fit: cover;
  border: 1px solid var(--mk-border);
  border-radius: 4px;
}

.picked-name {
  max-width: 180px;
  overflow: hidden;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.photo-body {
  min-height: 220px;
  padding: 14px 16px;
}

.photo-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
  gap: 14px;
}

.photo-card {
  margin: 0;
  overflow: hidden;
  border: 1px solid var(--mk-border);
  border-radius: 6px;
}

.photo {
  display: block;
  width: 100%;
  height: 130px;
  background: var(--mk-bg);
}

.photo-card figcaption {
  padding: 6px 8px;
  font-size: 12px;
}

.meta-line {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}

.note {
  margin-top: 4px;
  color: var(--mk-text-sub);
  overflow-wrap: anywhere;
}
</style>
