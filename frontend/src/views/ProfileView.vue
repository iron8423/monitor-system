<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { useUserStore } from '@/stores/user'

/**
 * 个人中心：只读资料 + 修改密码。
 *
 * 为什么资料是只读的：这份信息（公司 / 岗位 / 联系电话）是**组织属性**，不是本人的偏好设置。
 * 让每个人自己改，等于把「谁是谁」变成可自证的——出事之后追溯到的岗位与电话就没有意义了。
 * 修改渠道是管理员（管理端的用户管理是清单第 13 条，尚未做），所以这里只展示、并在页脚写清。
 *
 * 为什么进来要重新拉一次 `/auth/me`：本地 store 里的 user 是**登录那一刻**的快照，
 * 而资料可能在这之后被管理员改过（或本人换了浏览器、从别处登录）。不刷新的话，
 * 页面上会一直显示旧岗位——这种"看起来对、实际过期"的展示最难被发现。
 */
defineOptions({ name: 'ProfileView' })

const userStore = useUserStore()

const formRef = ref(null)
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
      // 两次一致性只能在本地判：送到服务端只送一个 newPassword，
      // 服务端根本不知道「确认框」的存在，也就无从校验。
      validator: (_rule, value, callback) =>
        value === form.newPassword ? callback() : callback(new Error('两次输入的新密码不一致')),
      trigger: 'blur',
    },
  ],
}

const profileRows = computed(() => [
  { k: '姓名', v: userStore.user?.displayName },
  { k: '账号', v: userStore.user?.username, mono: true },
  { k: '角色', v: userStore.roleLabel },
  { k: '公司', v: userStore.user?.organizationName },
  { k: '岗位', v: userStore.user?.jobTitle },
  { k: '联系电话', v: userStore.user?.phone, mono: true },
  { k: '邮箱', v: userStore.user?.email, mono: true },
])

onMounted(() => {
  // 失败也不拦页面：资料显示旧值总好过整页空白，用户仍能改密码
  userStore.loadCurrentUser().catch(() => {})
})

async function submit() {
  const ok = await formRef.value?.validate().catch(() => false)
  if (!ok) return

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
    formRef.value?.clearValidate()
  } catch (e) {
    // 静默请求：这里必须自己提示，否则点一下什么都没发生
    ElMessage.error(e?.message || '修改密码失败')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="profile-page">
    <div class="mk-panel">
      <div class="mk-panel-title">我的资料</div>
      <div class="fields">
        <div v-for="row in profileRows" :key="row.k" class="field">
          <span class="mk-muted field-k">{{ row.k }}</span>
          <span
            :class="{ 'mk-mono': row.mono, empty: row.v === null || row.v === undefined || row.v === '' }"
          >
            {{ row.v ?? '—' }}
          </span>
        </div>
      </div>
      <div class="mk-footnote">
        资料由管理员维护，本页只读——需要修改公司 / 岗位 / 联系方式请联系管理员。
      </div>
    </div>

    <div class="mk-panel">
      <div class="mk-panel-title">修改密码</div>
      <el-form
        ref="formRef"
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
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="submit">保存新密码</el-button>
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

.fields {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 10px 24px;
  padding: 16px;
}

.field {
  display: flex;
  gap: 10px;
  font-size: 13px;
}

.field-k {
  min-width: 68px;
}

.empty {
  color: var(--mk-text-sub);
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
