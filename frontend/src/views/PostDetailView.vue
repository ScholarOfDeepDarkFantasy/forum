<script setup>
import { ref, onMounted, computed, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { View, ChatDotRound, Star, StarFilled, CaretTop, Medal, Delete, Edit } from '@element-plus/icons-vue'
import { postApi, commentApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { usePostActions } from '@/composables/usePostActions'
import { useNotificationStore } from '@/stores/notifications'
import { formatDateTime } from '@/utils/format'

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

/**
 * 详情页展示模型：正文/作者/标签等字段来自详情接口，四个互动字段一律实时取自共享状态。
 *
 * 不能在 loadPost 时把 liked/favorited/likeCount/favoriteCount 合并进 post.value 快照：
 * 首页列表是响应式读 usePostActions 的同一份状态，详情页若快照副本，二者会在竞态下
 * 显示相反的状态。具体路径：用户在首页点了赞、请求尚未返回就打开帖子详情——
 * locallyToggled 已置位，于是 syncFromPost 有意不覆盖标志，计数也非 0 而被跳过，
 * 详情页便会渲染「未点赞 + 旧计数」，与首页列表相反，且此时点详情页按钮发的是
 * 取消请求、还会提示「已取消点赞」。同源响应式读取后，任何操作序列都不可能让两者不一致。
 */
const view = computed(() => {
  if (!post.value) return null
  const s = actions.stateFor(post.value.id)
  return {
    ...post.value,
    liked: s.liked,
    favorited: s.favorited,
    likeCount: s.likeCount,
    favoriteCount: s.favoriteCount
  }
})

async function loadPost() {
  const detail = await postApi.detail(route.params.id)
  // 只把服务端数据播种进共享状态；互动字段不在此处落盘，由 view 每次渲染时取用
  actions.syncFromPost(detail)
  post.value = detail
}

/**
 * 把 URL 锚点（#comment-N）滚动定位到对应评论并短暂高亮。
 *
 * 用 getElementById 而非 querySelector：hash 来自用户可控的 URL，直接喂给
 * querySelector 时非法选择器（如 #comment-]）会抛 SyntaxError。这里先用
 * /^comment-\d+$/ 收窄形态，非法 id 直接忽略。
 *
 * 已知限制：评论列表每页只取 10 条根评论（服务端按 createTime 升序返回根评论，
 * 子回复随父评论一起平铺）。REPLY 通知锚定的根评论若排在第 11 条之后，就不在本页
 * DOM 里。此时不再静默失败，而是在确实「还有更多评论可分页」（commentTotal > 10）
 * 时给一条非阻塞提示，避免用户以为点击没生效。评论已删除会命中同一分支，这是本轮
 * 接受的误报（见 README「已知限制」）。
 *
 * 同页内跳到另一条评论（仅 hash 变化、不重新挂载）这一步不需要额外 watch：
 * App.vue 的 router-view :key="$route.fullPath" 会在 hash 变化时强制重新挂载
 * 本组件，onMounted 会再次走到这里；若将来去掉那个 key，须在此处补回 watch。
 */
function scrollToHash() {
  const hash = route.hash
  if (!hash || !hash.startsWith('#comment-')) return
  const id = hash.slice(1)
  if (!/^comment-\d+$/.test(id)) return
  const el = document.getElementById(id)
  if (!el) {
    // 未找到：可能评论已删，也可能目标根评论在后面的评论分页里
    if (commentTotal.value > 10) {
      ElMessage.info('目标评论可能在后面的评论分页里，可翻页查看')
    }
    return
  }
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
  try {
    const r = await actions.toggleLike(post.value.id)
    ElMessage.success(r.active ? '已点赞' : '已取消点赞')
  } catch {
    // 失败回滚由 usePostActions 完成，面向用户的错误提示由 axios 拦截器统一给出；
    // 这里必须吞掉 rejection，否则点击处理器会抛出未处理的 Promise 拒绝
  }
}

async function toggleFavorite() {
  if (!auth.isLoggedIn) return router.push({ name: 'login', query: { redirect: route.fullPath } })
  try {
    const r = await actions.toggleFavorite(post.value.id)
    ElMessage.success(r.active ? '已收藏' : '已取消收藏')
  } catch {
    // 同上：回滚已做，提示归拦截器
  }
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
</script>

<template>
  <div class="detail" v-if="view">
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
          <span>· {{ formatDateTime(post.createTime) }}</span>
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
        <el-button round :type="view.liked ? 'primary' : 'default'" @click="toggleLike">
          👍 点赞 {{ view.likeCount }}
        </el-button>
        <el-button round :type="view.favorited ? 'warning' : 'default'" @click="toggleFavorite">
          <el-icon><component :is="view.favorited ? StarFilled : Star" /></el-icon> 收藏 {{ view.favoriteCount }}
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
            <span class="comment-time">{{ formatDateTime(c.createTime) }}</span>
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
                <span class="comment-time">{{ formatDateTime(r.createTime) }}</span>
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
  gap: var(--jf-space-4);
  padding-top: var(--jf-space-4);
}
.flags {
  display: flex;
  gap: 6px;
  margin-bottom: 6px;
}
.post-title {
  margin: 0 0 var(--jf-space-2);
  font-size: var(--jf-font-title-lg);
}
.post-meta {
  display: flex;
  gap: 6px;
  color: var(--jf-color-text-3);
  font-size: var(--jf-font-meta);
  align-items: center;
  flex-wrap: wrap;
}
.mod-actions {
  margin-top: 10px;
  display: flex;
  gap: var(--jf-space-2);
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
  gap: var(--jf-space-3);
}
.comments-card {
  margin-bottom: var(--jf-space-5);
}
.reply-alert {
  margin-bottom: 10px;
}
.comment-input {
  margin-bottom: 20px;
}
.send {
  margin-top: var(--jf-space-2);
}
.comment {
  padding: var(--jf-space-3) 0;
  border-bottom: 1px solid var(--jf-color-border);
}
.comment-head {
  display: flex;
  align-items: center;
  gap: var(--jf-space-2);
}
.comment-author {
  font-weight: 600;
  font-size: var(--jf-font-body);
}
/* #c0c4cc：占位/失效态浅灰，tokens.css 未提供对应令牌，保留字面量。
   若将来补令牌，应与 NotificationsView.vue 的 .notif-time 一并替换。 */
.comment-time {
  color: #c0c4cc;
  font-size: var(--jf-font-small);
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
  margin-top: var(--jf-space-1);
}
.replies {
  margin-top: 10px;
  padding-left: var(--jf-space-6);
  border-left: 2px solid var(--jf-color-border);
}
.reply {
  padding: var(--jf-space-2) 0;
}
.pager {
  margin-top: var(--jf-space-4);
  justify-content: center;
}
/* #fff8e6：锚点高亮的暖黄底，是一次性语义色，tokens.css 无对应令牌。
   已用 --jf-color-bg-hover(#f8f9fb) 等现有令牌替代会让高亮几乎不可见，
   故保留字面量并在此说明，避免后续「去字面量」清扫误换。 */
.anchor-highlight {
  background: #fff8e6;
  transition: background 0.4s ease;
}

/* 窄屏：评论缩进收窄，操作按钮允许换行并提高点按高度 */
@media (max-width: 768px) {
  .replies {
    padding-left: var(--jf-space-4);
  }
  .post-actions {
    flex-wrap: wrap;
  }
  .post-actions :deep(.el-button) {
    min-height: 36px;
  }
}
</style>
