<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import { tagApi } from '@/api'

const router = useRouter()
const keyword = ref('')
const pageData = ref({ content: [], totalElements: 0, totalPages: 0 })
const page = ref(0)
const size = 30
const loading = ref(false)
const error = ref('')

/**
 * 标签加载。失败时不把 rejection 抛给 onMounted：这里已有可见的错误态与重试入口，
 * 面向用户的提示由 axios 拦截器统一给出。
 */
async function load() {
  loading.value = true
  error.value = ''
  try {
    pageData.value = await tagApi.list({
      keyword: keyword.value || undefined,
      page: page.value,
      size
    })
  } catch {
    error.value = '标签加载失败'
    pageData.value = { content: [], totalElements: 0, totalPages: 0 }
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 0
  load()
}

function changePage(p) {
  page.value = p - 1
  load()
}

function openTag(name) {
  router.push(`/tags/${encodeURIComponent(name)}`)
}

onMounted(load)
</script>

<template>
  <el-card shadow="never" class="tags-card">
    <template #header>
      <div class="head">
        <span class="head-title">标签</span>
        <el-input
          v-model="keyword"
          placeholder="搜索标签…"
          clearable
          class="search-input"
          @keyup.enter="search"
          @clear="search"
        >
          <template #append>
            <el-button :icon="Search" @click="search" />
          </template>
        </el-input>
      </div>
    </template>

    <div v-loading="loading" class="tag-cloud">
      <div v-if="error" class="error-state">
        <span>{{ error }}</span>
        <el-button size="small" class="retry-btn" @click="load">重试</el-button>
      </div>
      <template v-else>
        <el-tag
          v-for="t in pageData.content"
          :key="t.id"
          size="large"
          effect="plain"
          class="tag"
          @click="openTag(t.name)"
        >
          {{ t.name }}<span class="count">{{ t.postCount }}</span>
        </el-tag>
      </template>
      <el-empty v-if="!error && !pageData.content.length" description="暂无标签" />
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
.tags-card {
  margin: var(--jf-space-4) auto;
  max-width: 860px;
}
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--jf-space-3);
  flex-wrap: wrap;
}
.head-title {
  font-weight: 600;
}
.search-input {
  max-width: 320px;
}
.tag-cloud {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: var(--jf-space-2);
  min-height: 96px;
}
.tag {
  cursor: pointer;
}
.tag:hover {
  color: var(--jf-color-primary);
  border-color: var(--jf-color-primary);
}
.count {
  margin-left: var(--jf-space-1);
  color: var(--jf-color-text-3);
  font-size: var(--jf-font-small);
}
.error-state {
  display: flex;
  align-items: center;
  gap: var(--jf-space-3);
  width: 100%;
  padding: var(--jf-space-4) 0;
  color: var(--jf-color-text-3);
}
</style>
