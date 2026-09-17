<script setup>
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { useUserStore } from '@/stores/user'

defineOptions({ name: 'LoginView' })

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const formRef = ref(null)
const loading = ref(false)
const errorMessage = ref('')

const form = reactive({
  username: 'admin',
  password: '123456',
})

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

// 演示账号（后端 DataInitializer 幂等创建，密码统一 123456）
const demoAccounts = [
  { username: 'admin', label: '管理员', desc: '全部权限 / 管理端' },
  { username: 'operator', label: '值班员', desc: '告警处置' },
  { username: 'analyst', label: '研判员', desc: '数据研判' },
  { username: 'maintainer', label: '运维员', desc: '设备巡检' },
]

const backendOrigin = computed(() => import.meta.env.VITE_BACKEND_ORIGIN || 'http://localhost:8080')

function useAccount(username) {
  form.username = username
  form.password = '123456'
  errorMessage.value = ''
}

async function handleSubmit() {
  errorMessage.value = ''
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await userStore.login({ username: form.username.trim(), password: form.password })
    ElMessage.success(`欢迎回来，${userStore.displayName}`)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await router.replace(redirect)
  } catch (error) {
    // 401 → 账号或密码错；网络类错误 → 后端没起来
    if (error.status === 401) {
      // 用服务端文案（后端返回「用户名或密码错误」），再加一句演示账号提示
      errorMessage.value = `${error.message || '用户名或密码错误'}（演示账号密码统一为 123456）`
    } else if (error.code === 'ERR_NETWORK' || error.code === 'ECONNABORTED') {
      errorMessage.value = `连不上后端服务，请确认已启动：${backendOrigin.value}`
    } else {
      errorMessage.value = error.message || '登录失败，请重试'
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login">
    <!-- 左：品牌区（后续 3D 大屏沿用这套深色底） -->
    <section class="brand">
      <div class="brand-grid" />
      <div class="brand-body">
        <div class="brand-tag">UGMS · 阶段 2</div>
        <h1 class="brand-title">通用监测管理系统</h1>
        <p class="brand-sub">变形 / 安全监测 · 3D 可视化大屏</p>

        <ul class="brand-points">
          <li><span class="dot" />雷达数据接入 → 实时测点值</li>
          <li><span class="dot" />累计形变 ±3mm 超限自动预警</li>
          <li><span class="dot" />3D 数字孪生大屏：自持离线山地模型 + 测点状态着色</li>
        </ul>

        <div class="brand-meta">
          <span>后端</span>
          <code>{{ backendOrigin }}</code>
        </div>
      </div>
    </section>

    <!-- 右：登录表单 -->
    <section class="panel">
      <div class="card">
        <h2 class="card-title">登录</h2>
        <p class="card-sub">请使用系统账号登录工作台</p>

        <el-alert
          v-if="errorMessage"
          class="card-alert"
          type="error"
          :title="errorMessage"
          show-icon
          :closable="false"
        />

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          size="large"
          @submit.prevent="handleSubmit"
        >
          <el-form-item label="用户名" prop="username">
            <el-input v-model="form.username" placeholder="admin" clearable autocomplete="username">
              <template #prefix><el-icon><User /></el-icon></template>
            </el-input>
          </el-form-item>

          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="123456"
              show-password
              autocomplete="current-password"
              @keyup.enter="handleSubmit"
            >
              <template #prefix><el-icon><Lock /></el-icon></template>
            </el-input>
          </el-form-item>

          <el-button
            class="submit"
            type="primary"
            size="large"
            :loading="loading"
            native-type="submit"
            @click="handleSubmit"
          >
            {{ loading ? '登录中…' : '登录' }}
          </el-button>
        </el-form>

        <el-divider><span class="divider-text">演示账号（密码 123456）</span></el-divider>

        <div class="accounts">
          <button
            v-for="account in demoAccounts"
            :key="account.username"
            type="button"
            class="account"
            :class="{ active: form.username === account.username }"
            @click="useAccount(account.username)"
          >
            <span class="account-label">{{ account.label }}</span>
            <span class="account-name">{{ account.username }}</span>
            <span class="account-desc">{{ account.desc }}</span>
          </button>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.login {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(420px, 0.85fr);
  height: 100%;
  min-height: 100vh;
}

/* ---------- 左：品牌区 ---------- */
.brand {
  position: relative;
  overflow: hidden;
  background:
    radial-gradient(circle at 18% 22%, rgba(31, 111, 235, 0.35), transparent 55%),
    radial-gradient(circle at 82% 78%, rgba(38, 208, 206, 0.22), transparent 58%),
    linear-gradient(160deg, var(--mk-dark-1), var(--mk-dark-2));
}

.brand-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(var(--mk-dark-line) 1px, transparent 1px),
    linear-gradient(90deg, var(--mk-dark-line) 1px, transparent 1px);
  background-size: 56px 56px;
  mask-image: radial-gradient(circle at 40% 40%, #000 10%, transparent 78%);
  opacity: 0.55;
}

.brand-body {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  height: 100%;
  padding: 0 8vw;
  color: #eaf2ff;
}

.brand-tag {
  align-self: flex-start;
  padding: 5px 12px;
  margin-bottom: 22px;
  font-size: 12px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: #7fd3ff;
  border: 1px solid rgba(127, 211, 255, 0.4);
  border-radius: 999px;
}

.brand-title {
  margin: 0;
  font-size: 40px;
  font-weight: 700;
  letter-spacing: 0.04em;
}

.brand-sub {
  margin: 14px 0 0;
  font-size: 16px;
  color: #9fb6d4;
}

.brand-points {
  margin: 38px 0 0;
  padding: 0;
  list-style: none;
  color: #c6d8ef;
}

.brand-points li {
  display: flex;
  align-items: center;
  margin-bottom: 14px;
  font-size: 14px;
}

.dot {
  width: 7px;
  height: 7px;
  margin-right: 10px;
  background: #35d0ba;
  border-radius: 50%;
  box-shadow: 0 0 10px rgba(53, 208, 186, 0.9);
}

.brand-meta {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-top: 44px;
  font-size: 12px;
  color: #7f93ad;
}

.brand-meta code {
  padding: 3px 8px;
  color: #9fd0ff;
  background: rgba(127, 211, 255, 0.08);
  border: 1px solid rgba(127, 211, 255, 0.2);
  border-radius: 4px;
}

/* ---------- 右：表单区 ---------- */
.panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
  background: var(--mk-panel);
}

.card {
  width: 100%;
  max-width: 380px;
}

.card-title {
  margin: 0;
  font-size: 26px;
  font-weight: 600;
  color: var(--mk-text);
}

.card-sub {
  margin: 8px 0 26px;
  color: var(--mk-text-sub);
}

.card-alert {
  margin-bottom: 18px;
}

.submit {
  width: 100%;
  margin-top: 6px;
  letter-spacing: 0.1em;
}

.divider-text {
  font-size: 12px;
  color: var(--mk-text-sub);
}

.accounts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.account {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 12px;
  text-align: left;
  cursor: pointer;
  background: #fafbfd;
  border: 1px solid var(--mk-border);
  border-radius: 8px;
  transition: all 0.15s ease;
}

.account:hover {
  border-color: #b7cdf5;
  background: #f3f7ff;
}

.account.active {
  border-color: var(--mk-primary);
  box-shadow: 0 0 0 3px rgba(31, 111, 235, 0.1);
}

.account-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--mk-text);
}

.account-name {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  color: var(--mk-primary);
}

.account-desc {
  font-size: 11px;
  color: var(--mk-text-sub);
}

@media (max-width: 960px) {
  .login {
    grid-template-columns: 1fr;
  }

  .brand {
    display: none;
  }
}
</style>
