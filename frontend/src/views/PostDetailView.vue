<script setup>
import { ref, onMounted, computed, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { View, ChatDotRound, Star, StarFilled, CaretTop, Medal, Delete, Edit } from '@element-plus/icons-vue'
import { postApi, commentApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { usePostActions } from '@/composables/usePostActions'
import { useNotificationStore } from '@/stores/notifications'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const actions = usePostActions()
const notifications = useNotificationStore()

const post = ref(null)
const comments = ref([])
const commentPage = ref(0)
const commentTotal = ref(0)
const commentForm = ref({ parentId: null, content: '' })
const replyTo = ref(null) // 被回复的评论
const submitting = ref(false)

const isAuthor = computed(() => auth.user && post.value && post.value.authorId === auth.user.id)

async function loadPost() {
  post.value = await postApi.detail(route.params.id)
  actions.syncFromPost(post.value)
}

/**
 * 把 URL 锚点（#comment-N）滚动定位到对应评论并短暂高亮。
 *
 * 用 getElementById 而非 querySelector：hash 来自用户可控的 URL，直接喂给
 * querySelector 时非法选择器（如 #comment-]）会抛 SyntaxError。
 * id 不是 #comment-N 形态或元素不存在（评论已删、在其它评论分页）时静默返回。
 * 同页内跳到另一条评论只改 hash、不重新挂载组件，由调用方 watch 触发本函数。
 */
function scrollToHash() {
  const hash = route.hash
  if (!hash || !hash.startsWith('#comment-')) return
  const el = document.getElementById(hash.slice(1))
  if (!el) return
  el.scrollIntoView({ behavior: 'smooth', block: 'center' })
  el.classList.add('anchor-highlight')
  setTimeout(() => el.classList.remove('anchor-highlight'), 2000)
}

async function loadComments(page = 0) {
  const data = await commentApi.list(route.params.id, { page, size: 10 })
  comments.value = data.content
  commentTotal.value = data.totalElements
  commentPage.value = page
}

async function submitComment() {
  if (!auth.isLoggedIn) {
    router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }
  if (!commentForm.value.content.trim()) {
    ElMessage.warning('请输入评论内容')
    return
  }
  submitting.value = true
  try {
    const payload = { content: commentForm.value.content }
    if (replyTo.value) payload.parentId = replyTo.value.id
    await commentApi.create(route.params.id, payload)
    ElMessage.success('评论成功')
    commentForm.value.content = ''
    replyTo.value = null
    await loadComments(0)
    await loadPost()
    notifications.refreshUnread()
  } finally {
    submitting.value = false
  }
}

function startReply(comment) {
  replyTo.value = comment
}

function cancelReply() {
  replyTo.value = null
}

async function removeComment(comment) {
  await ElMessageBox.confirm('确定删除该评论吗？', '提示', { type: 'warning' })
  await commentApi.remove(comment.id)
  ElMessage.success('已删除')
  await loadComments(commentPage.value)
  await loadPost()
}

async function toggleLike() {
  if (!auth.isLoggedIn) return router.push({ name: 'login', query: { redirect: route.fullPath } })
  const r = await actions.toggleLike(post.value.id)
  post.value = { ...post.value, likeCount: r.count, liked: r.active }
  ElMessage.success(r.active ? '已点赞' : '已取消点赞')
}

async function toggleFavorite() {
  if (!auth.isLoggedIn) return router.push({ name: 'login', query: { redirect: route.fullPath } })
  const r = await actions.toggleFavorite(post.value.id)
  post.value = { ...post.value, favoriteCount: r.count }
  ElMessage.success(r.active ? '已收藏' : '已取消收藏')
}

async function removePost() {
  await ElMessageBox.confirm('确定删除该帖子吗？（软删除，不可恢复显示）', '提示', { type: 'warning' })
  await postApi.remove(post.value.id)
  ElMessage.success('已删除')
  router.push('/')
}

async function moderate(key, value) {
  const updated = key === 'pinned' ? await postApi.pin(post.value.id, value) : await postApi.exquisite(post.value.id, value)
  post.value = updated
  ElMessage.success('操作成功')
}

function fmt(t) {
  return t ? t.replace('T', ' ').slice(0, 16) : ''
}

function canDeleteComment(c) {
  if (!auth.user) return false
  return c.authorId === auth.user.id || auth.isModerator
}

onMounted(async () => {
  await loadPost()
  await loadComments()
  // 评论渲染完毕后再定位：nextTick 让 DOM 跟上本轮 await 拿到的数据
  await nextTick()
  scrollToHash()
})

// 已在本页时再次点击指向另一条评论的通知（仅 hash 变化）不会重新挂载组件
watch(() => route.hash, scrollToHash)
</script>

<template>
  <div class="detail" v-if="post">
    <!-- 帖子主体 -->
    <el-card shadow="never">
      <div class="post-head">
        <div class="flags">
          <el-tag v-if="post.isPinned" type="danger" size="small"><el-icon><CaretTop /></el-icon> 置顶</el-tag>
          <el-tag v-if="post.isExquisite" type="warning" size="small"><el-icon><Medal /></el-icon> 精华</el-tag>
        </div>
        <h1 class="post-title">{{ post.title }}</h1>
        <div class="post-meta">
          <span>{{ post.authorNickname || post.authorUsername }}</span>
          <span>· {{ post.boardName }}</span>
          <span>· {{ fmt(post.createTime) }}</span>
          <span>· <el-icon><View /></el-icon> {{ post.viewCount }}</span>
        </div>
        <div class="mod-actions" v-if="auth.isModerator || isAuthor">
          <el-button v-if="isAuthor" size="small" :icon="Edit" @click="router.push(`/posts/${post.id}/edit`)">编辑</el-button>
          <el-button v-if="isAuthor || auth.isModerator" size="small" type="danger" plain :icon="Delete" @click="removePost">删除</el-button>
        </div>
        <div class="mod-actions" v-if="auth.isModerator">
          <el-button size="small" :type="post.isPinned ? 'info' : 'danger'" plain @click="moderate('pinned', !post.isPinned)">
            {{ post.isPinned ? '取消置顶' : '置顶' }}
          </el-button>
          <el-button size="small" :type="post.isExquisite ? 'info' : 'warning'" plain @click="moderate('exquisite', !post.isExquisite)">
            {{ post.isExquisite ? '取消精华' : '加精' }}
          </el-button>
        </div>
      </div>

      <el-divider />
      <div class="post-content">{{ post.content }}</div>
      <div class="post-tags" v-if="post.tags?.length">
        <el-tag v-for="t in post.tags" :key="t" size="small" type="info" effect="plain">{{ t }}</el-tag>
      </div>

      <div class="post-actions">
        <el-button round :type="post.liked ? 'primary' : 'default'" @click="toggleLike">
          👍 点赞 {{ post.likeCount }}
        </el-button>
        <el-button round @click="toggleFavorite">
          <el-icon><Star /></el-icon> 收藏 {{ post.favoriteCount }}
        </el-button>
      </div>
    </el-card>

    <!-- 评论 -->
    <el-card shadow="never" class="comments-card">
      <template #header>
        <span>评论 ({{ post.commentCount }})</span>
      </template>

      <!-- 回复提示 -->
      <el-alert
        v-if="replyTo"
        :title="`回复 @${replyTo.authorNickname || replyTo.authorUsername}`"
        type="info"
        closable
        @close="cancelReply"
        class="reply-alert"
      />

      <div class="comment-input">
        <el-input
          v-model="commentForm.content"
          type="textarea"
          :rows="3"
          :placeholder="auth.isLoggedIn ? '友善评论…' : '登录后即可评论'"
          maxlength="1000"
          show-word-limit
        />
        <el-button type="primary" :loading="submitting" class="send" @click="submitComment">发表评论</el-button>
      </div>

      <template v-if="comments.length">
        <div v-for="c in comments" :key="c.id" :id="`comment-${c.id}`" class="comment">
          <div class="comment-head">
            <el-avatar :size="26">{{ (c.authorNickname || c.authorUsername || '?').slice(0, 1) }}</el-avatar>
            <span class="comment-author">{{ c.authorNickname || c.authorUsername }}</span>
            <span class="comment-time">{{ fmt(c.createTime) }}</span>
          </div>
          <div class="comment-content" :class="{ deleted: c.deleted }">{{ c.content }}</div>
          <div class="comment-actions" v-if="!c.deleted">
            <el-button text size="small" @click="startReply(c)">回复</el-button>
            <el-button v-if="canDeleteComment(c)" text size="small" type="danger" @click="removeComment(c)">删除</el-button>
          </div>

          <!-- 楼中楼 -->
          <div v-if="c.replies?.length" class="replies">
            <div v-for="r in c.replies" :key="r.id" :id="`comment-${r.id}`" class="reply">
              <div class="comment-head">
                <el-avatar :size="22">{{ (r.authorNickname || r.authorUsername || '?').slice(0, 1) }}</el-avatar>
                <span class="comment-author">{{ r.authorNickname || r.authorUsername }}</span>
                <span class="comment-time">{{ fmt(r.createTime) }}</span>
              </div>
              <div class="comment-content" :class="{ deleted: r.deleted }">{{ r.content }}</div>
              <div class="comment-actions" v-if="!r.deleted">
                <el-button text size="small" @click="startReply(r)">回复</el-button>
                <el-button v-if="canDeleteComment(r)" text size="small" type="danger" @click="removeComment(r)">删除</el-button>
              </div>
            </div>
          </div>
        </div>

        <el-pagination
          v-if="commentTotal > 10"
          layout="prev, pager, next"
          :total="commentTotal"
          :page-size="10"
          :current-page="commentPage + 1"
          class="pager"
          @current-change="(p) => loadComments(p - 1)"
        />
      </template>
      <el-empty v-else description="还没有评论，来抢沙发" :image-size="80" />
    </el-card>
  </div>
</template>

<style scoped>
.detail {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding-top: 16px;
}
.flags {
  display: flex;
  gap: 6px;
  margin-bottom: 6px;
}
.post-title {
  margin: 0 0 8px;
  font-size: 22px;
}
.post-meta {
  display: flex;
  gap: 6px;
  color: #909399;
  font-size: 13px;
  align-items: center;
  flex-wrap: wrap;
}
.mod-actions {
  margin-top: 10px;
  display: flex;
  gap: 8px;
}
.post-content {
  white-space: pre-wrap;
  line-height: 1.8;
  font-size: 15px;
}
.post-tags {
  margin-top: 14px;
  display: flex;
  gap: 6px;
}
.post-actions {
  margin-top: 18px;
  display: flex;
  gap: 12px;
}
.comments-card {
  margin-bottom: 24px;
}
.reply-alert {
  margin-bottom: 10px;
}
.comment-input {
  margin-bottom: 20px;
}
.send {
  margin-top: 8px;
}
.comment {
  padding: 12px 0;
  border-bottom: 1px solid #f0f0f0;
}
.comment-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.comment-author {
  font-weight: 600;
  font-size: 14px;
}
.comment-time {
  color: #c0c4cc;
  font-size: 12px;
}
.comment-content {
  margin-top: 6px;
  line-height: 1.7;
  white-space: pre-wrap;
}
.comment-content.deleted {
  color: #c0c4cc;
  font-style: italic;
}
.comment-actions {
  margin-top: 4px;
}
.replies {
  margin-top: 10px;
  padding-left: 32px;
  border-left: 2px solid #f0f0f0;
}
.reply {
  padding: 8px 0;
}
.pager {
  margin-top: 16px;
  justify-content: center;
}
.anchor-highlight {
  background: #fff8e6;
  transition: background 0.4s ease;
}
</style>
