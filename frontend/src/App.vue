<script setup>
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Bell, User, SwitchButton, Connection, Setting } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'
import { useNotificationStream } from '@/composables/useNotificationStream'
import ThemeToggle from '@/components/ThemeToggle.vue'
import HeaderSearch from '@/components/HeaderSearch.vue'
import UserAvatar from '@/components/UserAvatar.vue'

const router = useRouter()
const auth = useAuthStore()
const notifications = useNotificationStore()

// 实时通知：登录后自动连 SSE，登出/卸载自动断开；断线退避与兜底轮询都在 composable 内
useNotificationStream()

async function handleUserCommand(command) {
  if (command === 'profile') {
    router.push('/profile')
  } else if (command === 'admin') {
    router.push('/admin')
  } else if (command === 'logout') {
    // 等后端尽力（成功或失败都已在 store 内部处理完）再宣布结果，
    // 否则后端 15s 超时窗口里提示已经出现，与事实不符。
    await auth.logout()
    // 角标/列表/分页等会话状态由 useNotificationStream 的 token watch 统一清空，
    // 这里不再各自清一半，避免登出/换号两条路径的重置逻辑漂移。
    ElMessage.success('已退出登录')
    router.push('/')
  }
}

onMounted(() => {
  // 带 token 刷新/直接进入页面时先拉一次未读数；登录成功后的刷新由登录页负责。
  // 他人产生的新通知现在走 SSE 实时事件，不再需要 120s 全局轮询——
  // 连接断开期间由 composable 临时恢复 120s 轮询，重连成功后停止。
  if (!auth.isLoggedIn) return
  notifications.refreshUnread()
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

        <router-link to="/tags" class="nav-link">标签</router-link>

        <HeaderSearch />

        <div class="nav-actions">
          <ThemeToggle />
          <template v-if="auth.isLoggedIn">
            <el-badge :value="notifications.unreadCount" :hidden="notifications.unreadCount === 0" class="bell-badge">
              <el-button :icon="Bell" circle text @click="router.push('/notifications')" />
            </el-badge>
            <el-dropdown @command="handleUserCommand">
              <span class="user-chip">
                <UserAvatar
                  :url="auth.user?.avatarUrl"
                  :name="auth.user?.nickname || auth.user?.username"
                  :size="28"
                />
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
  /* 原来是 space-between（brand / 标签 / actions 三段）；插入搜索框后改成左对齐分组，
     由 .nav-actions 的 margin-left:auto 把右侧操作区推到行尾 */
  justify-content: flex-start;
  gap: var(--jf-space-2);
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
.nav-link {
  margin-left: var(--jf-space-4);
  color: var(--jf-color-text-3);
}
.nav-link.router-link-active {
  color: var(--jf-color-primary);
  font-weight: 600;
}
.nav-actions {
  display: flex;
  align-items: center;
  gap: var(--jf-space-3);
  margin-left: auto;
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
