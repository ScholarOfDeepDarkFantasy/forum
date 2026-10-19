<script setup>
import { ref, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Search, Plus } from '@element-plus/icons-vue'
import { boardApi, postApi } from '@/api'
import { usePostActions } from '@/composables/usePostActions'
import { useAuthStore } from '@/stores/auth'
import PostListItem from '@/components/PostListItem.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const actions = usePostActions()

const boards = ref([])
const pageData = ref({ content: [], totalElements: 0, totalPages: 0 })
const query = ref({
  boardId: route.query.boardId ? Number(route.query.boardId) : null,
  keyword: route.query.keyword || '',
  page: 0,
  size: 10
})
const loading = ref(false)

async function loadBoards() {
  boards.value = await boardApi.list()
}

async function loadPosts() {
  loading.value = true
  try {
    const data = await postApi.list(query.value)
    // 列表响应里的 liked/favorited 播种进共享状态，使首页与详情页读到同一份标志
    data.content.forEach((p) => actions.syncFromPost(p))
    pageData.value = data
  } finally {
    loading.value = false
  }
}

/**
 * 列表行内的点赞：游客先跳登录，已登录则切换共享状态。
 *
 * 计数一律取自共享状态而非 p.likeCount：toggleLike 返回的 count 是最新的，
 * 直接渲染响应字段会在下次翻页/搜索重新拉取前显示旧值，与详情页不一致。
 * syncFromPost 的「仅当本地为 0 才播种」规则保证了本地已切换过的计数
 * 不会被后续的陈旧列表负载回退。
 */
async function toggleLike(p) {
  if (!auth.isLoggedIn) {
    router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }
  try {
    await actions.toggleLike(p.id)
  } catch {
    // 失败回滚已由 usePostActions 完成，面向用户的提示由 axios 拦截器统一给出；
    // 这里吞掉 rejection，避免点击处理器抛出未处理的 Promise 拒绝
  }
}

function search() {
  query.value.page = 0
  loadPosts()
}

function selectBoard(id) {
  query.value.boardId = id
  query.value.page = 0
  loadPosts()
}

function changePage(p) {
  query.value.page = p - 1
  loadPosts()
}

onMounted(loadBoards)
watch(
  () => route.query,
  () => {
    if (route.name === 'home') {
      query.value.boardId = route.query.boardId ? Number(route.query.boardId) : null
      query.value.keyword = route.query.keyword || ''
      loadPosts()
    }
  }
)
loadPosts()
</script>

<template>
  <div class="home">
    <!-- 板块导航 -->
    <el-card shadow="never" class="board-bar">
      <div class="board-list">
        <el-tag
          :type="query.boardId === null ? 'primary' : 'info'"
          effect="plain"
          class="board-tag"
          @click="selectBoard(null)"
        >
          全部
        </el-tag>
        <el-tag
          v-for="b in boards"
          :key="b.id"
          :type="query.boardId === b.id ? 'primary' : 'info'"
          effect="plain"
          class="board-tag"
          @click="selectBoard(b.id)"
        >
          {{ b.name }} ({{ b.postCount }})
        </el-tag>
      </div>
    </el-card>

    <!-- 搜索栏 -->
    <div class="toolbar">
      <el-input
        v-model="query.keyword"
        placeholder="搜索帖子标题/内容…"
        clearable
        class="search-input"
        @keyup.enter="search"
        @clear="search"
      >
        <template #append>
          <el-button :icon="Search" @click="search" />
        </template>
      </el-input>
      <el-button type="primary" :icon="Plus" @click="router.push('/posts/create')">发帖</el-button>
    </div>

    <!-- 帖子列表 -->
    <el-card shadow="never" v-loading="loading">
      <template v-if="pageData.content.length">
        <PostListItem
          v-for="p in pageData.content"
          :key="p.id"
          :post="p"
          @like="toggleLike"
        />
      </template>
      <el-empty v-else description="暂无帖子" />

      <el-pagination
        v-if="pageData.totalPages > 1"
        layout="prev, pager, next"
        :total="pageData.totalElements"
        :page-size="query.size"
        :current-page="query.page + 1"
        class="jf-pager"
        @current-change="changePage"
      />
    </el-card>
  </div>
</template>

<style scoped>
.home {
  display: flex;
  flex-direction: column;
  gap: var(--jf-space-4);
  padding-top: var(--jf-space-4);
}
.board-list {
  display: flex;
  flex-wrap: wrap;
  gap: var(--jf-space-2);
}
.board-tag {
  cursor: pointer;
}
.toolbar {
  display: flex;
  gap: var(--jf-space-3);
}
.search-input {
  flex: 1;
}


/* 窄屏：板块标签横滑、工具栏堆叠，避免标题被挤压换行 */
@media (max-width: 768px) {
  .board-list {
    flex-wrap: nowrap;
    overflow-x: auto;
    scrollbar-width: none;
    -webkit-overflow-scrolling: touch;
  }
  .board-list::-webkit-scrollbar {
    display: none;
  }
  .board-tag {
    flex-shrink: 0;
  }
  .toolbar {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
