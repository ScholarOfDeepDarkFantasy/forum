<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { View, ChatDotRound, Star, CaretTop, Medal } from '@element-plus/icons-vue'
import { usePostActions } from '@/composables/usePostActions'
import { formatRelative } from '@/utils/format'
import { highlightSegments } from '@/utils/search'

const props = defineProps({
  post: { type: Object, required: true },
  // 搜索页传入：标题与片段里命中的关键词会被 <mark> 高亮；其它页面不传，渲染与原来一致
  keyword: { type: String, default: '' },
  // 搜索页传入：正文命中处的纯文本窗口（服务端截取 ±80 字符），前端 buildSnippet 再清洗
  snippet: { type: String, default: '' }
})
const emit = defineEmits(['like'])

const router = useRouter()
const actions = usePostActions()
const state = computed(() => actions.stateFor(props.post.id))

/** 标题分段：没有 keyword 时退化为单段，模板结构不需要分支 */
const titleSegments = computed(() =>
  props.keyword
    ? highlightSegments(props.post.title, props.keyword)
    : [{ text: props.post.title, hit: false }]
)

/** 片段分段：snippet 为空时数组为空，模板用 v-if 直接不渲染整行 */
const snippetSegments = computed(() =>
  props.snippet ? highlightSegments(props.snippet, props.keyword) : []
)

function openPost() {
  router.push(`/posts/${props.post.id}`)
}

function openTag(tag) {
  router.push(`/tags/${encodeURIComponent(tag)}`)
}
</script>

<template>
  <div class="post-item" @click="openPost">
    <div class="post-main">
      <div class="post-title-line">
        <el-tag v-if="post.isPinned" type="danger" size="small" class="flag">
          <el-icon><CaretTop /></el-icon>置顶
        </el-tag>
        <el-tag v-if="post.isExquisite" type="warning" size="small" class="flag">
          <el-icon><Medal /></el-icon>精华
        </el-tag>
        <span class="post-title">
          <template v-for="(seg, i) in titleSegments" :key="i">
            <mark v-if="seg.hit" class="hit">{{ seg.text }}</mark>
            <template v-else>{{ seg.text }}</template>
          </template>
        </span>
      </div>
      <p v-if="snippet" class="post-snippet">
        <template v-for="(seg, i) in snippetSegments" :key="i">
          <mark v-if="seg.hit" class="hit">{{ seg.text }}</mark>
          <template v-else>{{ seg.text }}</template>
        </template>
      </p>
      <div class="post-meta">
        <span>{{ post.authorNickname || post.authorUsername }}</span>
        <span>· {{ post.boardName }}</span>
        <span>· {{ formatRelative(post.createTime) }}</span>
        <span v-if="post.tags?.length" class="tags">
          <el-tag
            v-for="t in post.tags"
            :key="t"
            size="small"
            type="info"
            effect="plain"
            @click.stop="openTag(t)"
          >
            {{ t }}
          </el-tag>
        </span>
      </div>
    </div>
    <div class="post-stats">
      <span><el-icon><View /></el-icon>{{ post.viewCount }}</span>
      <span><el-icon><ChatDotRound /></el-icon>{{ post.commentCount }}</span>
    </div>
    <button
      type="button"
      class="like-btn"
      :class="{ active: state.liked }"
      :title="state.liked ? '取消点赞' : '点赞'"
      @click.stop="emit('like', post)"
    >
      <el-icon><Star /></el-icon>{{ state.likeCount }}
    </button>
  </div>
</template>

<style scoped>
.post-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px var(--jf-space-2);
  border-bottom: 1px solid var(--jf-color-border);
  cursor: pointer;
}
.post-item:last-of-type {
  border-bottom: none;
}
.post-item:hover {
  background: var(--jf-color-bg-hover);
}
.post-main {
  flex: 1;
  min-width: 0;
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
  font-size: var(--jf-font-title-md);
  font-weight: 600;
}
/* 命中词高亮：用浅色底 + 继承文字色，暗色令牌下也不会变成白底黑字 */
.hit {
  background: var(--jf-color-anchor-bg);
  color: inherit;
  padding: 0 1px;
  border-radius: var(--jf-radius-sm);
}
/* 片段只占一行：超出用省略号，避免搜索结果行高被长正文撑开 */
.post-snippet {
  margin: 4px 0 0;
  color: var(--jf-color-text-3);
  font-size: var(--jf-font-meta);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.post-meta {
  margin-top: 6px;
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--jf-color-text-3);
  font-size: var(--jf-font-meta);
  flex-wrap: wrap;
}
.tags {
  display: inline-flex;
  gap: var(--jf-space-1);
}
/* 标签 chip 可点击；不设 cursor 时与普通信息文本无异，用户不会想到可以点 */
.tags :deep(.el-tag) {
  cursor: pointer;
}
.tags :deep(.el-tag:hover) {
  color: var(--jf-color-primary);
  border-color: var(--jf-color-primary);
}
.post-stats {
  display: flex;
  gap: 14px;
  color: var(--jf-color-text-3);
  font-size: var(--jf-font-meta);
  flex-shrink: 0;
}
.post-stats span {
  display: inline-flex;
  align-items: center;
  gap: var(--jf-space-1);
}
.like-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--jf-space-1);
  margin-left: var(--jf-space-3);
  padding: var(--jf-space-1) var(--jf-space-2);
  border: 1px solid var(--jf-color-border);
  border-radius: var(--jf-radius-sm);
  background: var(--jf-color-bg-card);
  color: var(--jf-color-text-3);
  font-size: var(--jf-font-meta);
  font-family: inherit;
  line-height: 1.5;
  cursor: pointer;
  flex-shrink: 0;
}
.like-btn:hover {
  background: var(--jf-color-bg-hover);
}
.like-btn.active {
  border-color: var(--jf-color-primary);
  color: var(--jf-color-primary);
}

/* 窄屏：帖子行允许换行，避免标题被统计与点赞按钮挤成一条窄缝 */
@media (max-width: 768px) {
  .post-item {
    align-items: flex-start;
    flex-wrap: wrap;
  }
  .post-meta {
    gap: var(--jf-space-1);
  }
  .post-stats {
    margin-left: var(--jf-space-2);
  }
}
</style>
