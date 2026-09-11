<script setup>
import { computed, onMounted, ref } from 'vue'

import { fetchMe } from '@/api/auth'
import { getToken } from '@/utils/token'
import { useUserStore } from '@/stores/user'

defineOptions({ name: 'HomeView' })

const userStore = useUserStore()

const checking = ref(false)
const meResult = ref(null)
const meError = ref('')
const backendDown = ref(false)

const apiBase = import.meta.env.VITE_API_BASE_URL || '/api'
const backendOrigin = import.meta.env.VITE_BACKEND_ORIGIN || 'http://localhost:8080'

const maskedToken = computed(() => {
  const token = getToken()
  if (!token) return '（无）'
  return `${token.slice(0, 18)}… （共 ${token.length} 字符）`
})

// 规划路线：也是阶段 1 的验收清单
const roadmap = [
  { title: '工程骨架（Vite + Vue3 + Element Plus + ECharts）', done: true },
  { title: '登录页 + JWT 存储 + 请求头注入 + 路由守卫', done: true },
  { title: '总览 / 测点页（points、latest、series、summary）', done: false, stage: '阶段 2' },
  { title: 'Cesium 3D 大屏（地形 + 标点着色 + 弹窗 + 时间轴/热力图）', done: false, stage: '阶段 3' },
  { title: '告警中心 + 管理端（alarms / actions / 规则 CRUD）', done: false, stage: '阶段 4' },
  { title: '无人机影像挂点（media 上传 / 列表 / 预览）', done: false, stage: '阶段 5' },
]

async function check() {
  checking.value = true
  meError.value = ''
  backendDown.value = false
  try {
    meResult.value = await fetchMe()
  } catch (error) {
    meResult.value = null
    if (error.code === 'ERR_NETWORK' || error.code === 'ECONNABORTED') {
      backendDown.value = true
      meError.value = `连不上后端：${backendOrigin}`
    } else {
      meError.value = error.message || '校验失败'
    }
  } finally {
    checking.value = false
  }
}

onMounted(check)
</script>

<template>
  <div class="home">
    <el-card shadow="never" class="welcome">
      <div class="welcome-main">
        <div>
          <h2 class="welcome-title">
            你好，{{ userStore.displayName }}
            <el-tag size="small" effect="plain">{{ userStore.roleLabel }}</el-tag>
          </h2>
          <p class="welcome-sub">
            阶段 1（工程骨架 + 登录）已完成。下面这张「环境自检」卡片用来确认前后端已经连通，
            确认无误后我们就进入阶段 2（总览 / 测点页）。
          </p>
        </div>
        <el-button type="primary" :loading="checking" @click="check">重新检测</el-button>
      </div>
    </el-card>

    <el-row :gutter="16" class="mt">
      <el-col :xs="24" :md="12">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>环境自检</span>
              <el-tag v-if="meResult" type="success" size="small" effect="dark">后端连通</el-tag>
              <el-tag v-else-if="backendDown" type="danger" size="small" effect="dark">后端未启动</el-tag>
              <el-tag v-else type="warning" size="small" effect="dark">校验异常</el-tag>
            </div>
          </template>

          <el-alert
            v-if="backendDown"
            type="error"
            :closable="false"
            show-icon
            title="检测不到后端"
            :description="`请另开一个终端执行：cd backend → .\\mvnw.cmd spring-boot:run（${backendOrigin}）`"
          />
          <el-alert
            v-else-if="meError"
            class="mb"
            type="warning"
            :closable="false"
            show-icon
            :title="meError"
          />

          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="API 前缀">{{ apiBase }}</el-descriptions-item>
            <el-descriptions-item label="后端地址">{{ backendOrigin }}</el-descriptions-item>
            <el-descriptions-item label="JWT（localStorage）">
              <span class="mono">{{ maskedToken }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="GET /auth/me">
              <template v-if="meResult">
                <span class="mono">
                  id={{ meResult.id }} · {{ meResult.username }} · {{ meResult.role }}
                </span>
              </template>
              <span v-else class="dim">未取到</span>
            </el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>

      <el-col :xs="24" :md="12">
        <el-card shadow="never">
          <template #header><span>阶段路线</span></template>
          <ul class="roadmap">
            <li v-for="item in roadmap" :key="item.title" :class="{ done: item.done }">
              <el-icon v-if="item.done" class="ok"><CircleCheckFilled /></el-icon>
              <el-icon v-else class="todo"><Clock /></el-icon>
              <span class="roadmap-title">{{ item.title }}</span>
              <el-tag v-if="item.stage" size="small" type="info" effect="plain">{{ item.stage }}</el-tag>
            </li>
          </ul>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.welcome-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
}

.welcome-title {
  display: flex;
  gap: 10px;
  align-items: center;
  margin: 0;
  font-size: 20px;
}

.welcome-sub {
  max-width: 720px;
  margin: 10px 0 0;
  line-height: 1.7;
  color: var(--mk-text-sub);
}

.mt {
  margin-top: 16px;
}

.mb {
  margin-bottom: 12px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.mono {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
}

.dim {
  color: var(--mk-text-sub);
}

.roadmap {
  padding: 0;
  margin: 0;
  list-style: none;
}

.roadmap li {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 7px 0;
  font-size: 13px;
  color: var(--mk-text-sub);
}

.roadmap li.done {
  color: var(--mk-text);
}

.roadmap-title {
  flex: 1;
}

.ok {
  color: #35b37e;
}

.todo {
  color: #c0c4cc;
}
</style>
