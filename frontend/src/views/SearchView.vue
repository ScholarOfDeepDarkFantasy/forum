<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import { postApi } from '@/api'
import { usePostActions } from '@/composables/usePostActions'
import { useAuthStore } from '@/stores/auth'
import { buildSnippet } from '@/utils/search'
import PostListItem from '@/components/PostListItem.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const actions = usePostActions()

const input = ref('')
const pageData = ref({ content: [], totalElements: 0, totalPages: 0 })
const page = ref(0)
const size = 10
const loading = ref(false)
const error = ref('')
const searched = ref(false)

/** 当前关键词：以地址栏为准，刷新、前进后退、顶栏跳转都走同一条路径 */
const keyword = computed(() => (typeof route.query.q === 'string' ? route.query.q.trim() : ''))

/**
 * 同一路由记录内换词时组件可能被复用（App.vue 的 :key 目前会重建，这里仍防一手）：
 * 两次请求乱序返回时用自增序号丢弃过期响应，避免慢请求的旧结果覆盖新关键词。
 */
let requestSeq = 0

async function load() {
  const q = keyword.value
  input.value = q
  if (!q) {
    searched.value = false
    pageData.value = { content: [], totalElements: 0, totalPages: 0 }
    return
  }
  const seq = ++requestSeq
  searched.value = true
  loading.value = true
  error.value = ''
  try {
    const data = await postApi.search({ keyword: q, page: page.value, size })
    if (seq !== requestSeq) return
    // 与首页一致：把互动字段播种进共享状态，点进详情后仍是同一份计数
    data.content.forEach((p) => actions.syncFromPost(p))
    pageData.value = data
  } catch {
    if (seq !== requestSeq) return
    error.value = '搜索失败，请稍后重试'
    pageData.value = { content: [], totalElements: 0, totalPages: 0 }
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

function submit() {
  const q = input.value.trim()
  if (!q) return
  // 交给 watch(keyword) 统一触发加载：replace 不新增历史记录，连续搜索不会塞满后退按钮
  router.replace({ name: 'search', query: { q } })
}

function clear() {
  router.replace({ name: 'search' })
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

// 关键词变化（页面内搜索、顶栏搜索、浏览器前进后退）时回到第一页重新加载
watch(keyword, () => {
  page.value = 0
  load()
})

load()
</script>

<template>
  <el-card shadow="never" class="search-card">
    <template #header>
      <div class="head">
        <span class="title">搜索</span>
        <span v-if="searched && !error" class="total">共 {{ pageData.totalElements }} 条结果</span>
      </div>
      <div class="search-bar">
        <el-input
          v-model="input"
          placeholder="搜索帖子标题/内容…"
          maxlength="50"
          clearable
          class="search-input"
          @keyup.enter="submit"
          @clear="clear"
        >
          <template #append>
            <el-button :icon="Search" aria-label="搜索" @click="submit" />
          </template>
        </el-input>
      </div>
    </template>

    <div v-loading="loading">
      <div v-if="error" class="error-state">
        <span>{{ error }}</span>
        <el-button size="small" class="retry-btn" @click="load">重试</el-button>
      </div>
      <el-empty v-else-if="!searched" description="输入关键词开始搜索" />
      <template v-else>
        <PostListItem
          v-for="p in pageData.content"
          :key="p.id"
          :post="p"
          :keyword="keyword"
          :snippet="buildSnippet(p.snippet || '', keyword)"
          @like="toggleLike"
        />
        <el-empty
          v-if="!pageData.content.length"
          :description="`没有找到与 “${keyword}” 相关的帖子`"
        />
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
.search-card {
  margin: var(--jf-space-4) auto;
  max-width: 860px;
}
.head {
  display: flex;
  align-items: center;
  gap: var(--jf-space-3);
}
.title {
  font-weight: 600;
}
.total {
  margin-left: auto;
  color: var(--jf-color-text-3);
  font-size: var(--jf-font-meta);
}
.search-bar {
  margin-top: var(--jf-space-3);
}
.error-state {
  display: flex;
  align-items: center;
  gap: var(--jf-space-3);
  padding: var(--jf-space-4) 0;
  color: var(--jf-color-text-3);
}
</style>
