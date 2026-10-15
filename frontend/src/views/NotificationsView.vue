<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { notificationApi } from '@/api'

const list = ref({ content: [], totalElements: 0, totalPages: 0 })
const page = ref(0)
const loading = ref(false)

async function load(p = 0) {
  loading.value = true
  try {
    list.value = await notificationApi.mine({ page: p, size: 10 })
    page.value = p
  } finally {
    loading.value = false
  }
}

async function markAll() {
  await notificationApi.markAllRead()
  ElMessage.success('已全部标记为已读')
  await load(page.value)
}

function fmt(t) {
  return t ? t.replace('T', ' ').slice(0, 16) : ''
}

onMounted(() => load())
</script>

<template>
  <el-card shadow="never" class="notif-card">
    <template #header>
      <div class="head">
        <span>我的通知</span>
        <el-button size="small" @click="markAll">全部已读</el-button>
      </div>
    </template>

    <template v-if="list.content.length">
      <div v-for="n in list.content" :key="n.id" class="notif" :class="{ unread: !n.isRead }">
        <el-tag size="small" :type="n.type === 'LIKE' ? 'danger' : n.type === 'FAVORITE' ? 'warning' : 'info'">
          {{ { REPLY: '回复', LIKE: '点赞', FAVORITE: '收藏', SYSTEM: '系统' }[n.type] || n.type }}
        </el-tag>
        <span class="notif-content">{{ n.content }}</span>
        <span class="notif-time">{{ fmt(n.createTime) }}</span>
        <el-tag v-if="n.postId" size="small" type="primary" effect="plain">{{ n.postTitle }}</el-tag>
        <el-tag v-if="!n.isRead" size="small" type="success" effect="dark">未读</el-tag>
      </div>
      <el-pagination
        v-if="list.totalPages > 1"
        layout="prev, pager, next"
        :total="list.totalElements"
        :page-size="10"
        :current-page="page + 1"
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
