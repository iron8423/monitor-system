<script setup>
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'

import { useUserStore } from '@/stores/user'

/**
 * 自助注册。
 *
 * 规则（2026-09-17 用户定案）：
 *   · 个人信息**由本人填**——姓名、公司、岗位、联系电话、邮箱，注册后可在个人中心自改；
 *   · **不能注册成管理员**（后端只收 OPERATOR / ANALYST / MAINTAINER）：自助注册若能选 ADMIN，
 *     等于任何人都能把自己提成管理员。要管理员权限，得由既有管理员在用户管理里改角色；
 *   · 注册成功**直接进系统**（后端返回令牌），不必再去登录页重敲一遍；
 *   · 账号名被已删除的账号占用时，本人可以**重新注册同一个账号**（后端复用那行墓碑记录）。
 */
defineOptions({ name: 'RegisterView' })

const router = useRouter()
const userStore = useUserStore()

const formRef = ref(null)
const submitting = ref(false)

const form = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  displayName: '',
  company: '',
  jobTitle: '',
  phone: '',
  email: '',
  role: 'OPERATOR',
})

/** 可选角色：只有非管理员（与后端 RegisterRequest 的校验一致） */
const ROLE_OPTIONS = [
  { value: 'OPERATOR', label: '值班员' },
  { value: 'ANALYST', label: '研判员' },
  { value: 'MAINTAINER', label: '运维员' },
]

const rules = {
  username: [
    { required: true, message: '请输入账号', trigger: 'blur' },
    { pattern: /^[A-Za-z0-9_.-]{3,32}$/, message: '只能用字母、数字、下划线、点或短横线，长度 3–32', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, max: 64, message: '密码长度需为 8–64 位', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_r, v, cb) => (v === form.password ? cb() : cb(new Error('两次输入的密码不一致'))),
      trigger: 'blur',
    },
  ],
  displayName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
  role: [{ required: true, message: '请选择角色', trigger: 'change' }],
}

async function submit() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  submitting.value = true
  try {
    const { confirmPassword, ...payload } = form
    await userStore.register(payload)
    ElMessage.success('注册成功，已直接登录')
    router.replace('/home')
  } catch (e) {
    ElMessage.error(e?.message || '注册失败')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="register-page">
    <div class="mk-panel card">
      <div class="mk-panel-title">
        注册账号
        <span class="mk-muted sub">个人信息由本人填写，注册后可在「个人中心」自行修改</span>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="96px" @submit.prevent>
        <el-form-item label="账号" prop="username">
          <el-input v-model="form.username" autocomplete="username" placeholder="登录名，3–32 位" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" show-password autocomplete="new-password" placeholder="至少 8 位" />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input v-model="form.confirmPassword" type="password" show-password autocomplete="new-password" />
        </el-form-item>
        <el-form-item label="姓名" prop="displayName">
          <el-input v-model="form.displayName" />
        </el-form-item>
        <el-form-item label="公司" prop="company">
          <el-input v-model="form.company" placeholder="公司 / 单位名（没有会自动建立）" />
        </el-form-item>
        <el-form-item label="岗位" prop="jobTitle">
          <el-input v-model="form.jobTitle" />
        </el-form-item>
        <el-form-item label="联系电话" prop="phone">
          <el-input v-model="form.phone" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="form.email" />
        </el-form-item>
        <el-form-item label="角色" prop="role">
          <el-select v-model="form.role" style="width: 100%">
            <el-option v-for="r in ROLE_OPTIONS" :key="r.value" :label="r.label" :value="r.value" />
          </el-select>
          <div class="field-hint">管理员账号不能自助注册（需要管理员权限请由管理员分配）</div>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="submit">注册并进入系统</el-button>
          <el-button link type="primary" @click="router.push('/login')">返回登录</el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.register-page {
  display: flex;
  justify-content: center;
  padding: 32px 16px;
}

.card {
  width: 100%;
  max-width: 560px;
  padding-bottom: 8px;
}

.sub {
  margin-left: 10px;
  font-size: 12px;
  font-weight: 400;
}

.field-hint {
  font-size: 12px;
  color: var(--mk-text-sub);
  line-height: 1.5;
}
</style>
