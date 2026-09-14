<script setup>
import { computed, ref, watch } from 'vue'

import * as api from '@/api/monitor'

/**
 * 测点影像画廊（验收第 6 条「点一个监测点……照片/影像全出来」的那一半）。
 *
 * 三处共用：测点详情的「影像」页签、`/media` 总览页、3D 大屏的点击浮窗。
 * 三处形状不同（网格 / 分组网格 / 单张缩略图），所以**只把「拉列表 + 拼地址」收在这里**，
 * 布局交给 `columns` 与默认插槽——否则又变成一份口径抄三遍。
 *
 * 地址一律走 {@link api.mediaContentUrl}：`<img>` 带不了请求头，token 必须进 query。
 * 直接写 `item.url` 会得到一堆碎图，且**不会报错**（图片加载失败不走 axios 拦截器）。
 */

defineOptions({ name: 'MediaGallery' })

const props = defineProps({
  pointId: { type: [Number, String], default: null },
  /** 缩略图列数；0 = 不设网格，由父组件用自己的样式接管（大屏浮窗就是这么用的） */
  columns: { type: Number, default: 4 },
  emptyText: { type: String, default: '该测点暂无影像' },
  /** 缩略图边长，跟着容器走 */
  size: { type: String, default: '96px' },
  /** 只展示最新 N 张；0 = 全部。大屏浮窗只要一张缩略图 */
  max: { type: Number, default: 0 },
  /** 无影像时连空状态一起藏掉（大屏浮窗那种「有就显示、没有就别占地方」的场合） */
  hideEmpty: { type: Boolean, default: false },
})

const emit = defineEmits(['loaded'])

const items = ref([])
const loading = ref(false)
const failed = ref(false)

/**
 * 展示用的切片。**`max` 只截展示，不截 `preview-src-list`**——
 * 大屏浮窗只显示一张缩略图，但点开仍应能翻到该点的全部影像，
 * 截错了就变成「浮窗里只能看一张」，而且不会报错。
 */
const shown = computed(() => (props.max > 0 ? items.value.slice(0, props.max) : items.value))

/** 大图查看用：`el-image` 的 preview-src-list 要的是「带 token 的整串地址」 */
const urls = computed(() => items.value.map((m) => api.mediaContentUrl(m)))

async function reload() {
  if (!props.pointId) {
    items.value = []
    return
  }
  loading.value = true
  failed.value = false
  try {
    items.value = (await api.listPointMedia(props.pointId)) || []
  } catch {
    items.value = []
    failed.value = true
  } finally {
    loading.value = false
    emit('loaded', items.value)
  }
}

defineExpose({ reload })

// 切测点必须重拉：`pointId` 变了而列表没变，就会出现「B 测点显示 A 的照片」
watch(() => props.pointId, reload, { immediate: true })
</script>

<template>
  <div v-loading="loading" class="gallery" :class="{ 'no-empty': hideEmpty }">
    <div
      v-if="items.length"
      class="grid"
      :style="columns > 0 ? { gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))` } : {}"
    >
      <div v-for="(m, i) in shown" :key="m.mediaId" class="cell">
        <!-- 预览列表给全量：点开一张后能左右翻，不必退出来再点下一张 -->
        <el-image
          :src="urls[i]"
          :preview-src-list="urls"
          :initial-index="i"
          fit="cover"
          preview-teleported
          class="thumb"
          :style="{ height: size }"
        >
          <template #error>
            <!-- 取图失败最常见的原因就是 token 丢了/过期，把话说出来，别只给个破图图标 -->
            <div class="thumb-error">
              <span>取图失败</span>
              <span class="mk-muted">登录可能已过期</span>
            </div>
          </template>
        </el-image>
        <div class="meta">
          <span class="mk-mono mk-muted time">{{ m.takenAt || '未填拍摄时间' }}</span>
          <span v-if="m.note" class="note" :title="m.note">{{ m.note }}</span>
        </div>
      </div>
    </div>

    <el-empty
      v-else-if="!loading && !hideEmpty"
      :description="failed ? '影像列表拉取失败' : emptyText"
      :image-size="60"
    />
  </div>
</template>

<style scoped>
.gallery {
  min-height: 80px;
}

/* 藏了空状态就别再撑着这块高度，否则大屏浮窗里会多一截空白 */
.gallery.no-empty {
  min-height: 0;
}

.grid {
  display: grid;
  gap: 12px;
  padding: 12px 16px;
}

.cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.thumb {
  width: 100%;
  border: 1px solid var(--mk-border);
  border-radius: 4px;
  cursor: zoom-in;
  background: var(--mk-bg);
}

.thumb-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  height: 100%;
  font-size: 12px;
  color: var(--mk-level-alarm);
}

.meta {
  display: flex;
  flex-direction: column;
  gap: 1px;
  font-size: 12px;
}

.time {
  font-size: 11px;
}

/* 备注可能很长（上传时随手写的一句），超一行截断，完整内容走 title */
.note {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--mk-text);
}
</style>
