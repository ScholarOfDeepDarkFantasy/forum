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
  // 与服务端一致：按 Unicode 码点计 1~50。UI 的 maxlength 按 UTF-16 码元计数，
  // 放宽到 100 个码元（避免 25 个 emoji 就被输入框截断），真正上限由下面的码点校验兜底
  if (!q || Array.from(q).length > 50) return
  // 已在搜索页且关键词不变：不重复 push，避免历史记录里堆同样地址
  if (route.name === 'search' && route.query.q === q) return
  router.push({ name: 'search', query: { q } })
}

/**
 * 清空输入框：ElInput 会先清空 v-model 再 emit('clear')，此时 submit() 只会看到空串。
 * 设计意图是「清空 = 回到搜索页的空态」：只在搜索页把地址栏的 q 去掉；其它页面不跳转。
 */
function onClear() {
  if (route.name !== 'search') return
  router.push({ name: 'search' })
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
      maxlength="100"
      clearable
      class="search-box"
      @keyup.enter="submit"
      @clear="onClear"
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
      @click="openSearchPage"
    />
  </div>
</template>

<style scoped>
/* 宽度固定在 220px：顶栏用 flex-start + .nav-actions 的 margin-left:auto 分成左右两组，
   输入框自适应会把导航挤到换行，固定宽度则不会随内容跳动 */
.search-box {
  width: 220px;
}
</style>
