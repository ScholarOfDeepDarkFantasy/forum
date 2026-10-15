<script setup>
import { ref, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Search, Plus, View, ChatDotRound, CaretTop, Medal } from '@element-plus/icons-vue'
import { boardApi, postApi } from '@/api'

const route = useRoute()
const router = useRouter()

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
    pageData.value = await postApi.list(query.value)
  } finally {
    loading.value = false
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

function fmtTime(t) {
  return t ? t.replace('T', ' ').slice(0, 16) : ''
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
        <div v-for="p in pageData.content" :key="p.id" class="post-item" @click="router.push(`/posts/${p.id}`)">
          <div class="post-main">
            <div class="post-title-line">
              <el-tag v-if="p.isPinned" type="danger" size="small" class="flag">
                <el-icon><CaretTop /></el-icon>置顶
              </el-tag>
              <el-tag v-if="p.isExquisite" type="warning" size="small" class="flag">
                <el-icon><Medal /></el-icon>精华
              </el-tag>
              <span class="post-title">{{ p.title }}</span>
            </div>
            <div class="post-meta">
              <span>{{ p.authorNickname || p.authorUsername }}</span>
              <span>· {{ p.boardName }}</span>
              <span>· {{ fmtTime(p.createTime) }}</span>
              <span v-if="p.tags?.length" class="tags">
                <el-tag v-for="t in p.tags" :key="t" size="small" type="info" effect="plain">{{ t }}</el-tag>
              </span>
            </div>
          </div>
          <div class="post-stats">
            <span><el-icon><View /></el-icon>{{ p.viewCount }}</span>
            <span><el-icon><ChatDotRound /></el-icon>{{ p.commentCount }}</span>
          </div>
        </div>
      </template>
      <el-empty v-else description="暂无帖子" />

      <el-pagination
        v-if="pageData.totalPages > 1"
        layout="prev, pager, next"
        :total="pageData.totalElements"
        :page-size="query.size"
        :current-page="query.page + 1"
        class="pager"
        @current-change="changePage"
      />
    </el-card>
  </div>
</template>

<style scoped>
.home {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding-top: 16px;
}
.board-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.board-tag {
  cursor: pointer;
}
.toolbar {
  display: flex;
  gap: 12px;
}
.search-input {
  flex: 1;
}
.post-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 8px;
  border-bottom: 1px solid #f0f0f0;
  cursor: pointer;
}
.post-item:last-of-type {
  border-bottom: none;
}
.post-item:hover {
  background: #f8f9fb;
}
.post-title-line {
  display: flex;
  align-items: center;
  gap: 6px;
}
.flag {
  flex-shrink: 0;
}
.post-title {
  font-size: 16px;
  font-weight: 600;
}
.post-meta {
  margin-top: 6px;
  display: flex;
  align-items: center;
  gap: 6px;
  color: #909399;
  font-size: 13px;
  flex-wrap: wrap;
}
.tags {
  display: inline-flex;
  gap: 4px;
}
.post-stats {
  display: flex;
  gap: 14px;
  color: #909399;
  font-size: 13px;
  flex-shrink: 0;
}
.post-stats span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.pager {
  margin-top: 16px;
  justify-content: center;
}
</style>
