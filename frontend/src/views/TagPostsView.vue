<script setup>
import { ref, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { tagApi } from '@/api'
import { usePostActions } from '@/composables/usePostActions'
import { useAuthStore } from '@/stores/auth'
import PostListItem from '@/components/PostListItem.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const actions = usePostActions()

const pageData = ref({ content: [], totalElements: 0, totalPages: 0 })
const page = ref(0)
const size = 10
const loading = ref(false)
const error = ref('')

/**
 * 标签帖子加载。标签不存在时后端返回空页而不是 404，因此「未知标签」与
 * 「标签下还没帖子」共用空状态；只有网络/服务异常才进入错误态。
 *
 * 同一路由记录内切换标签（/tags/java → /tags/spring）时组件会被复用，
 * 两个请求可能乱序返回；用递增序号丢弃过期响应，避免慢请求把旧标签的
 * 列表写回新标签页。
 */
let requestSeq = 0

async function load() {
  const seq = ++requestSeq
  loading.value = true
  error.value = ''
  try {
    const data = await tagApi.posts(route.params.name, { page: page.value, size })
    if (seq !== requestSeq) return
    // 与首页一致：把列表的互动字段播种进共享状态，点进详情后仍是同一份计数
    data.content.forEach((p) => actions.syncFromPost(p))
    pageData.value = data
  } catch {
    if (seq !== requestSeq) return
    error.value = '标签帖子加载失败'
    pageData.value = { content: [], totalElements: 0, totalPages: 0 }
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

function changePage(p) {
  page.value = p - 1
  load()
}

async function toggleLike(p) {
  if (!auth.isLoggedIn) {
    router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }
  try {
    await actions.toggleLike(p.id)
  } catch {
    // 失败回滚由 usePostActions 完成，提示由 axios 拦截器统一给出
  }
}

// 路由参数变化时组件不会重新挂载，必须手动重置分页并重新拉取
watch(
  () => route.params.name,
  () => {
    page.value = 0
    load()
  }
)

onMounted(load)
</script>

<template>
  <el-card shadow="never" class="tag-posts-card">
    <template #header>
      <div class="head">
        <router-link to="/tags" class="back-link">← 全部标签</router-link>
        <span class="title">标签：{{ route.params.name }}</span>
        <span class="total">{{ pageData.totalElements }} 篇</span>
      </div>
    </template>

    <div v-loading="loading">
      <div v-if="error" class="error-state">
        <span>{{ error }}</span>
        <el-button size="small" class="retry-btn" @click="load">重试</el-button>
      </div>
      <template v-else>
        <PostListItem v-for="p in pageData.content" :key="p.id" :post="p" @like="toggleLike" />
        <el-empty v-if="!pageData.content.length" description="该标签下暂无帖子" />
      </template>
    </div>

    <el-pagination
      v-if="!error && pageData.totalPages > 1"
      layout="prev, pager, next"
      :total="pageData.totalElements"
      :page-size="size"
      :current-page="page + 1"
      class="jf-pager"
      @current-change="changePage"
    />
  </el-card>
</template>

<style scoped>
.tag-posts-card {
  margin: var(--jf-space-4) auto;
  max-width: 860px;
}
.head {
  display: flex;
  align-items: center;
  gap: var(--jf-space-3);
  flex-wrap: wrap;
}
.back-link {
  color: var(--jf-color-text-3);
  font-size: var(--jf-font-meta);
}
.back-link:hover {
  color: var(--jf-color-primary);
}
.title {
  font-weight: 600;
}
.total {
  margin-left: auto;
  color: var(--jf-color-text-3);
  font-size: var(--jf-font-meta);
}
.error-state {
  display: flex;
  align-items: center;
  gap: var(--jf-space-3);
  padding: var(--jf-space-4) 0;
  color: var(--jf-color-text-3);
}
</style>
