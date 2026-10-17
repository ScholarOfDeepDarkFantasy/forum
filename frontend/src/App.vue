<script setup>
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Bell, User, SwitchButton, Connection, Setting } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'

const router = useRouter()
const auth = useAuthStore()
const notifications = useNotificationStore()

function handleUserCommand(command) {
  if (command === 'profile') {
    router.push('/profile')
  } else if (command === 'admin') {
    router.push('/admin')
  } else if (command === 'logout') {
    auth.logout()
    notifications.unreadCount = 0
    ElMessage.success('已退出登录')
    router.push('/')
  }
}

onMounted(() => {
  // 未登录时不注册定时器：refreshUnread 会打到后端换回 401，拦截器随即弹「请先登录」并整页跳转 /login，
  // 游客停在首页也会被莫名踢走。兜底轮询只对已登录会话有意义；
  // 本分支只覆盖「带着 token 刷新/直接进入页面」的会话，登录页在登录成功后另行触发一次。
  if (!auth.isLoggedIn) return
  notifications.refreshUnread()
  // 兜底轮询：主要刷新由点赞/收藏/评论等操作后主动触发
  setInterval(() => notifications.refreshUnread(), 120_000)
})
</script>

<template>
  <el-container class="layout">
    <el-header class="header">
      <div class="header-inner">
        <router-link to="/" class="brand">
          <el-icon :size="22"><Connection /></el-icon>
          <span>JavaForum</span>
        </router-link>

        <div class="nav-actions">
          <template v-if="auth.isLoggedIn">
            <el-badge :value="notifications.unreadCount" :hidden="notifications.unreadCount === 0" class="bell-badge">
              <el-button :icon="Bell" circle text @click="router.push('/notifications')" />
            </el-badge>
            <el-dropdown @command="handleUserCommand">
              <span class="user-chip">
                <el-avatar :size="28">{{ (auth.user?.nickname || auth.user?.username || '?').slice(0, 1) }}</el-avatar>
                <span class="username">{{ auth.user?.nickname || auth.user?.username }}</span>
              </span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="profile" :icon="User">个人中心</el-dropdown-item>
                  <el-dropdown-item v-if="auth.isAdmin" command="admin" :icon="Setting">管理后台</el-dropdown-item>
                  <el-dropdown-item command="logout" :icon="SwitchButton" divided>退出登录</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
          <template v-else>
            <el-button text @click="router.push('/login')">登录</el-button>
            <el-button type="primary" @click="router.push('/register')">注册</el-button>
          </template>
        </div>
      </div>
    </el-header>

    <el-main class="main">
      <router-view :key="$route.fullPath" />
    </el-main>

    <el-footer class="footer">
      JavaForum · Spring Boot 3 + Vue 3 MVP
    </el-footer>
  </el-container>
</template>

<style scoped>
.layout {
  min-height: 100vh;
}
.header {
  background: var(--jf-color-bg-card);
  border-bottom: 1px solid var(--jf-color-border);
  position: sticky;
  top: 0;
  z-index: 100;
}
.header-inner {
  max-width: 1100px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 100%;
}
.brand {
  display: flex;
  align-items: center;
  gap: var(--jf-space-2);
  font-size: 20px;
  font-weight: 700;
  color: var(--jf-color-primary);
}
.nav-actions {
  display: flex;
  align-items: center;
  gap: var(--jf-space-3);
}
.user-chip {
  display: flex;
  align-items: center;
  gap: var(--jf-space-2);
  cursor: pointer;
}
.username {
  font-size: var(--jf-font-body);
}
.main {
  max-width: 1100px;
  margin: 0 auto;
  width: 100%;
}
.footer {
  text-align: center;
  color: var(--jf-color-text-3);
  font-size: var(--jf-font-meta);
  padding: 20px 0;
}
.bell-badge :deep(.el-badge__content) {
  top: 6px;
  right: 10px;
}
</style>
