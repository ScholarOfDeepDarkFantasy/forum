<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { PAGE_SIZE, useNotificationStore } from '@/stores/notifications'
import { resolveNotificationTarget } from '@/utils/notificationLink'

const router = useRouter()
const notifications = useNotificationStore()
const loading = ref(false)

async function load(p = 0) {
  loading.value = true
  try {
    await notifications.loadList(p)
  } finally {
    loading.value = false
  }
}

async function markAll() {
  await notifications.markAllRead()
}

function openNotification(n) {
  const target = targetOf(n)
  if (!target) return
  router.push(target)
}

/**
 * 行跳转目标；SYSTEM 与无 postId 的通知返回 null（不可点击，也不显示手型光标）。
 * 模板里 class 与 click 都读这一个函数，避免同一行在渲染期重复调用两次
 * resolveNotificationTarget。
 */
function targetOf(n) {
  return resolveNotificationTarget(n)
}

function fmt(t) {
  return t ? t.replace('T', ' ').slice(0, 16) : ''
}

onMounted(() => {
  load()
  // App.vue 只在应用挂载时刷一次角标，之后靠 120s 轮询兜底；用户停留在通知页时
  // 其它标签页/设备可能已把通知读掉，进入本页时重新对账一次，避免角标与列表不一致。
  notifications.refreshUnread()
})
</script>

<template>
  <el-card shadow="never" class="notif-card">
    <template #header>
      <div class="head">
        <span>我的通知</span>
        <el-button size="small" @click="markAll">全部已读</el-button>
      </div>
    </template>

    <template v-if="notifications.list.content.length">
      <div
        v-for="n in notifications.list.content"
        :key="n.id"
        class="notif"
        :class="{ unread: !n.isRead, clickable: !!targetOf(n) }"
        @click="openNotification(n)"
      >
        <el-tag size="small" :type="n.type === 'LIKE' ? 'danger' : n.type === 'FAVORITE' ? 'warning' : 'info'">
          {{ { REPLY: '回复', LIKE: '点赞', FAVORITE: '收藏', SYSTEM: '系统' }[n.type] || n.type }}
        </el-tag>
        <span class="notif-content">{{ n.content }}</span>
        <span class="notif-time">{{ fmt(n.createTime) }}</span>
        <el-tag v-if="n.postId" size="small" type="primary" effect="plain">{{ n.postTitle }}</el-tag>
        <el-tag v-if="!n.isRead" size="small" type="success" effect="dark">未读</el-tag>
      </div>
      <el-pagination
        v-if="notifications.list.totalPages > 1"
        layout="prev, pager, next"
        :total="notifications.list.totalElements"
        :page-size="PAGE_SIZE"
        :current-page="notifications.listPage + 1"
        class="pager"
        @current-change="(p) => load(p - 1)"
      />
    </template>
    <el-empty v-else description="暂无通知" />
  </el-card>
</template>

<style scoped>
.notif-card {
  margin: 16px auto;
  max-width: 860px;
}
.head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.notif {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 4px;
  border-bottom: 1px solid #f0f0f0;
}
.notif:last-of-type {
  border-bottom: none;
}
.notif.unread {
  background: #f0f7ff;
}
.notif.clickable {
  cursor: pointer;
}
.notif.clickable:hover {
  background: var(--jf-color-bg-hover);
}
.notif-content {
  flex: 1;
  font-size: 14px;
}
.notif-time {
  color: #c0c4cc;
  font-size: 12px;
  flex-shrink: 0;
}
.pager {
  margin-top: 16px;
  justify-content: center;
}
</style>
