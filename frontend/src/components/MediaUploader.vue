<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'

import * as api from '@/api/monitor'

/**
 * 影像上传（multipart）。挂到测点上，可带拍摄时间与备注。
 *
 * 走 `:http-request` 而不是 el-upload 自带的 XHR：后者不经过 `api/http.js` 那个 axios 实例，
 * 于是**没有 Authorization 头**、也**不走统一错误处理**——症状是上传永远 401，
 * 而提示只有一个笼统的「上传失败」。交给 `api.uploadMedia` 就两条都有了。
 *
 * 客户端先挡一道非图片：后端也挡（`06-media.sh` 有断言），但让用户等一个来回才知道选错了文件
 * 没道理。挡不住的（改扩展名的那种）仍由后端拒收。
 */

defineOptions({ name: 'MediaUploader' })

const props = defineProps({
  pointId: { type: [Number, String], default: null },
  /** 没有可选测点时可隐藏拍摄时间/备注，只留一个按钮（大屏浮窗那种场景） */
  compact: { type: Boolean, default: false },
})

const emit = defineEmits(['uploaded'])

const takenAt = ref('')
const note = ref('')
const uploading = ref(false)

function beforeUpload(file) {
  if (!file.type.startsWith('image/')) {
    ElMessage.error('只能上传图片（后端也只收图片）')
    return false
  }
  return true
}

/** el-upload 的 http-request 钩子：拿到 file 后换成我们自己的请求 */
async function doUpload({ file, onSuccess, onError }) {
  if (!props.pointId) {
    ElMessage.warning('请先选择测点')
    onError?.(new Error('未选测点'))
    return
  }
  uploading.value = true
  try {
    const vo = await api.uploadMedia(file, props.pointId, {
      // el-date-picker 给的是 'YYYY-MM-DD HH:mm:ss'，补成带时区的 ISO；
      // 这个字段后端原样存字符串，别在这里自作主张转 UTC。
      takenAt: takenAt.value ? `${takenAt.value.replace(' ', 'T')}+08:00` : undefined,
      note: note.value || undefined,
    })
    ElMessage.success(`已上传 ${vo?.mediaId || ''}`.trim())
    note.value = ''
    onSuccess?.(vo)
    emit('uploaded', vo)
  } catch (e) {
    // 错误提示已由 axios 拦截器统一弹过，这里只把失败回传给 el-upload 收尾
    onError?.(e)
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <div class="uploader">
    <el-upload
      :show-file-list="false"
      :before-upload="beforeUpload"
      :http-request="doUpload"
      accept="image/*"
      :disabled="uploading || !pointId"
    >
      <el-button size="small" type="primary" :loading="uploading" :disabled="!pointId">
        上传影像
      </el-button>
    </el-upload>

    <template v-if="!compact">
      <el-date-picker
        v-model="takenAt"
        type="datetime"
        size="small"
        placeholder="拍摄时间（可空）"
        value-format="YYYY-MM-DD HH:mm:ss"
        style="width: 190px"
      />
      <el-input
        v-model="note"
        size="small"
        placeholder="备注（可空）"
        maxlength="200"
        style="width: 200px"
      />
    </template>

    <span v-if="!pointId" class="mk-muted hint">先选测点</span>
  </div>
</template>

<style scoped>
.uploader {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.hint {
  font-size: 12px;
}
</style>
