<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const notifications = useNotificationStore()
const loading = ref(false)

const form = reactive({ username: '', password: '' })

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    await auth.login(form)
    // App.vue 的 onMounted 只对「挂载时就已登录」的会话刷新角标，登录发生在挂载之后，
    // 因此必须在这里主动拉一次，否则登录后角标会一直停在 0。
    // 不 await、也不捕获：refreshUnread 内部吞掉所有错误、永不 reject，
    // 计数晚几十毫秒到位可接受，而等待会白白拖慢跳转（失败更不能挡住导航）。
    notifications.refreshUnread()
    ElMessage.success('登录成功')
    router.push(route.query.redirect || '/')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <el-card shadow="never" class="auth-card">
      <h2 class="title">登录 JavaForum</h2>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="用户名" autofocus />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password placeholder="密码" @keyup.enter="submit" />
        </el-form-item>
        <el-button type="primary" class="submit" :loading="loading" native-type="submit">
          登录
        </el-button>
        <div class="tip">
          还没有账号？
          <router-link to="/register" class="link">立即注册</router-link>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.auth-page {
  display: flex;
  justify-content: center;
  padding-top: 60px;
}
.auth-card {
  width: 400px;
}
.title {
  margin: 0 0 16px;
  text-align: center;
}
.submit {
  width: 100%;
}
.tip {
  margin-top: 14px;
  text-align: center;
  color: #909399;
  font-size: 13px;
}
.link {
  color: #409eff;
}
</style>
