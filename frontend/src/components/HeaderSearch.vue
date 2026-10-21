<script setup>
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import { useResponsive } from '@/composables/useResponsive'

const route = useRoute()
const router = useRouter()
const { isMobile } = useResponsive()

const keyword = ref('')

function submit() {
  const q = keyword.value.trim()
  if (!q) return
  // 已在搜索页且关键词不变：不重复 push，避免历史记录里堆同样地址
  if (route.name === 'search' && route.query.q === q) return
  router.push({ name: 'search', query: { q } })
}

/** 窄屏没有输入框：直接把用户送到搜索页，在页面里输入 */
function openSearchPage() {
  if (route.name === 'search') return
  router.push({ name: 'search' })
}

// 顶栏输入框与地址栏保持一致：页面内搜索、浏览器前进后退、离开搜索页都会同步
watch(
  () => route.query.q,
  (q) => {
    keyword.value = typeof q === 'string' ? q : ''
  },
  { immediate: true }
)
</script>

<template>
  <div class="header-search">
    <el-input
      v-if="!isMobile"
      v-model="keyword"
      placeholder="搜索帖子"
      maxlength="50"
      clearable
      class="search-box"
      @keyup.enter="submit"
      @clear="submit"
    >
      <template #append>
        <el-button :icon="Search" aria-label="搜索" @click="submit" />
      </template>
    </el-input>
    <el-button
      v-else
      :icon="Search"
      circle
      text
      aria-label="搜索"
      class="search-btn"
      @click="openSearchPage"
    />
  </div>
</template>

<style scoped>
/* 宽度固定在 220px：顶栏是 space-between 之外的 flex 行，输入框自适应会把导航挤到换行 */
.search-box {
  width: 220px;
}
</style>
