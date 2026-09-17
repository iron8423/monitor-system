<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { useUserStore } from '@/stores/user'

/**
 * 个人中心：改自己的资料 + 改自己的密码。
 *
 * 口径（2026-09-17 用户定案）：**个人信息归本人**——注册时自己填，之后在这里自己改；
 * 管理员不参与（管理员只管看全部账号、改角色与启用、删账号，见管理端「用户」页签）。
 * 所以这一页是**可编辑**的，而不是只读展示。
 *
 * 页面里改不动的两项：**账号**（登录名兼审计线索）与**角色**（权限，由管理员配）。
 * 两者都只展示，避免「提交了但没生效」这种更糟的错觉。
 */
defineOptions({ name: 'ProfileView' })

const userStore = useUserStore()

// ---- 资料 ----
const profileRef = ref(null)
const savingProfile = ref(false)

const profile = reactive({
  displayName: '',
  company: '',
  jobTitle: '',
  phone: '',
  email: '',
})

const profileRules = {
  displayName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
}

/** 把 store 里的当前用户铺进表单；进页面时拉一次最新的 /auth/me 再铺 */
function syncProfile() {
  const u = userStore.user || {}
  profile.displayName = u.displayName || ''
  profile.company = u.organizationName || ''
  profile.jobTitle = u.jobTitle || ''
  profile.phone = u.phone || ''
  profile.email = u.email || ''
}

const identity = computed(() => ({
  username: userStore.user?.username || '—',
  roleLabel: userStore.roleLabel || '—',
}))

async function saveProfile() {
  if (!(await profileRef.value?.validate().catch(() => false))) return
  savingProfile.value = true
  try {
    await userStore.updateProfile({ ...profile })
    ElMessage.success('资料已保存')
  } catch (e) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    savingProfile.value = false
  }
}

// ---- 改密 ----
const pwdRef = ref(null)
const submitting = ref(false)

const form = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: '',
})

const rules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, max: 64, message: '新密码长度需为 8–64 位', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      // 两次一致性只能在本地判：送到服务端的只有一个 newPassword，
      // 服务端不知道「确认框」的存在，也就无从校验。
      validator: (_rule, value, callback) =>
        value === form.newPassword ? callback() : callback(new Error('两次输入的新密码不一致')),
      trigger: 'blur',
    },
  ],
}

async function submitPassword() {
  if (!(await pwdRef.value?.validate().catch(() => false))) return
  submitting.value = true
  try {
    await userStore.changePassword({
      oldPassword: form.oldPassword,
      newPassword: form.newPassword,
    })
    ElMessage.success('密码已修改；其它设备上的登录已失效，本页继续可用')
    form.oldPassword = ''
    form.newPassword = ''
    form.confirmPassword = ''
    pwdRef.value?.clearValidate()
  } catch (e) {
    // 静默请求：这里必须自己提示，否则点一下什么都没发生
    ElMessage.error(e?.message || '修改密码失败')
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  syncProfile()
  try {
    // 拉一次最新的：本地 store 里是登录那一刻的快照，可能在别处改过
    await userStore.loadCurrentUser()
  } catch {
    // 拉不到就用手上的快照，别让整页空白
  }
  syncProfile()
})
</script>

<template>
  <div class="profile-page">
    <div class="mk-panel">
      <div class="mk-panel-title">我的资料</div>

      <!-- 账号与角色只读：账号是登录名兼审计线索，角色是管理员配的权限 -->
      <div class="identity">
        <span class="mk-muted">账号</span>
        <span class="mk-mono">{{ identity.username }}</span>
        <span class="mk-muted">角色</span>
        <span>{{ identity.roleLabel }}</span>
      </div>

      <el-form
        ref="profileRef"
        :model="profile"
        :rules="profileRules"
        label-width="96px"
        class="pwd-form"
        @submit.prevent
      >
        <el-form-item label="姓名" prop="displayName">
          <el-input v-model="profile.displayName" />
        </el-form-item>
        <el-form-item label="公司" prop="company">
          <el-input v-model="profile.company" placeholder="填写公司 / 单位名" />
        </el-form-item>
        <el-form-item label="岗位" prop="jobTitle">
          <el-input v-model="profile.jobTitle" />
        </el-form-item>
        <el-form-item label="联系电话" prop="phone">
          <el-input v-model="profile.phone" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="profile.email" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="savingProfile" @click="saveProfile">保存资料</el-button>
          <span class="mk-muted hint">资料由本人维护；账号与角色不在这里改。</span>
        </el-form-item>
      </el-form>

      <div class="mk-footnote">
        账号与角色属于身份/权限，分别由「不能改名」与「管理员配置」决定，故本页只读展示。
      </div>
    </div>

    <div class="mk-panel">
      <div class="mk-panel-title">修改密码</div>
      <el-form
        ref="pwdRef"
        :model="form"
        :rules="rules"
        label-width="96px"
        class="pwd-form"
        @submit.prevent
      >
        <el-form-item label="原密码" prop="oldPassword">
          <el-input
            v-model="form.oldPassword"
            type="password"
            show-password
            autocomplete="current-password"
          />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input
            v-model="form.newPassword"
            type="password"
            show-password
            autocomplete="new-password"
          />
        </el-form-item>
        <el-form-item label="确认新密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            show-password
            autocomplete="new-password"
            @keyup.enter="submitPassword"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="submitPassword">保存新密码</el-button>
          <span class="mk-muted hint">新密码至少 8 位；修改后其它设备需要重新登录。</span>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.profile-page {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

@media (max-width: 1100px) {
  .profile-page {
    grid-template-columns: minmax(0, 1fr);
  }
}

.identity {
  display: flex;
  gap: 10px 24px;
  flex-wrap: wrap;
  padding: 12px 16px 0;
  font-size: 13px;
}

.pwd-form {
  padding: 16px 16px 4px;
  max-width: 520px;
}

.hint {
  margin-left: 12px;
  font-size: 12px;
}
</style>
