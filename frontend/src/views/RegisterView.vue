<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const formRef = ref()

const form = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  email: '',
  nickname: ''
})

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 50, message: '长度 3-50 个字符', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 位', trigger: 'blur' }
  ],
  confirmPassword: [
    {
      validator: (_, v, cb) => {
        if (v !== form.password) cb(new Error('两次密码不一致'))
        else cb()
      },
      trigger: 'blur'
    }
  ],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' }
  ]
}

async function submit() {
  await formRef.value.validate()
  loading.value = true
  try {
    await auth.register({
      username: form.username,
      password: form.password,
      email: form.email,
      nickname: form.nickname || undefined
    })
    ElMessage.success('注册成功，请登录')
    router.push('/login')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <el-card shadow="never" class="auth-card">
      <h2 class="title">注册 JavaForum</h2>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="3-50 个字符，支持中文/字母/数字/下划线" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="form.email" placeholder="email@example.com" />
        </el-form-item>
        <el-form-item label="昵称（可选）">
          <el-input v-model="form.nickname" placeholder="昵称" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" show-password placeholder="至少 6 位" />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input v-model="form.confirmPassword" type="password" show-password placeholder="再次输入密码" @keyup.enter="submit" />
        </el-form-item>
        <el-button type="primary" class="submit" :loading="loading" native-type="submit">
          注册
        </el-button>
        <div class="tip">
          已有账号？
          <router-link to="/login" class="link">直接登录</router-link>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.auth-page {
  display: flex;
  justify-content: center;
  padding-top: 40px;
}
.auth-card {
  width: 440px;
}
.title {
  margin: 0 0 var(--jf-space-4);
  text-align: center;
}
.submit {
  width: 100%;
}
.tip {
  margin-top: 14px;
  text-align: center;
  color: var(--jf-color-text-3);
  font-size: var(--jf-font-meta);
}
.link {
  color: var(--jf-color-primary);
}

/* 窄屏：卡片占满可用宽度，避免 440px 固定宽在手机上溢出；页面内边距同步收窄 */
@media (max-width: 768px) {
  .auth-card {
    width: 100%;
  }
  .auth-page {
    padding: var(--jf-space-4);
  }
}
</style>
