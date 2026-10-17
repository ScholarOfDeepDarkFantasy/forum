<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { PAGE_SIZE, useNotificationStore } from '@/stores/notifications'
import { resolveNotificationTarget } from '@/utils/notificationLink'

const router = useRouter()
const notifications = useNotificationStore()
const loading = ref(false)
/**
 * 全部已读的在途标志，与 loading 分开：loading 表示"列表正在拉取"（翻页也会置位），
 * 若共用，翻页期间按钮会闪 loading 且被禁用，markAll 的早退守卫还会把这次点击静默吞掉。
 * 这里只用于标记这个变更请求本身在途，防止连点造成响应乱序覆盖列表。
 */
const marking = ref(false)

async function load(p = 0) {
  loading.value = true
  try {
    await notifications.loadList(p)
  } finally {
    loading.value = false
  }
}

/**
 * 全部已读。成功后才提示：清空通知在本页不可撤销，而角标归零、行去掉加粗这类反馈
 * 很容易在浏览列表时被忽略。失败不需要 catch，axios 拦截器已统一弹出错误。
 */
async function markAll() {
  if (marking.value) return
  marking.value = true
  try {
    await notifications.markAllRead()
    ElMessage.success('已全部标记为已读')
  } finally {
    marking.value = false
  }
}

function openNotification(n) {
  const target = targetOf(n)
  if (!target) return
  router.push(target)
}

/**
 * 行跳转目标；SYSTEM 与无 postId 的通知返回 null（不可点击，也不显示手型光标）。
 * 模板里 class 与 click 都读这一个函数，避免同一行在渲染期重复调用两次
 * resolveNotificationTarget。
 */
function targetOf(n) {
  return resolveNotificationTarget(n)
}

function fmt(t) {
  return t ? t.replace('T', ' ').slice(0, 16) : ''
}

onMounted(() => {
  load()
  // App.vue 只在应用挂载时刷一次角标，之后靠 120s 轮询兜底；用户停留在通知页时
  // 其它标签页/设备可能已把通知读掉，进入本页时重新对账一次，避免角标与列表不一致。
  notifications.refreshUnread()
})
</script>

<template>
  <el-card shadow="never" class="notif-card">
    <template #header>
      <div class="head">
        <span>我的通知</span>
        <el-button size="small" :loading="marking" @click="markAll">全部已读</el-button>
      </div>
    </template>

    <template v-if="notifications.list.content.length">
      <div
        v-for="n in notifications.list.content"
        :key="n.id"
        class="notif"
        :class="{ unread: !n.isRead, clickable: !!targetOf(n) }"
        @click="openNotification(n)"
      >
        <el-tag size="small" :type="n.type === 'LIKE' ? 'danger' : n.type === 'FAVORITE' ? 'warning' : 'info'">
          {{ { REPLY: '回复', LIKE: '点赞', FAVORITE: '收藏', SYSTEM: '系统' }[n.type] || n.type }}
        </el-tag>
        <span class="notif-content">{{ n.content }}</span>
        <span class="notif-time">{{ fmt(n.createTime) }}</span>
        <el-tag v-if="n.postId" size="small" type="primary" effect="plain">{{ n.postTitle }}</el-tag>
        <el-tag v-if="!n.isRead" size="small" type="success" effect="dark">未读</el-tag>
      </div>
      <el-pagination
        v-if="notifications.list.totalPages > 1"
        layout="prev, pager, next"
        :total="notifications.list.totalElements"
        :page-size="PAGE_SIZE"
        :current-page="notifications.listPage + 1"
        class="pager"
        @current-change="(p) => load(p - 1)"
      />
    </template>
    <el-empty v-else description="暂无通知" />
  </el-card>
</template>

<style scoped>
.notif-card {
  margin: var(--jf-space-4) auto;
  max-width: 860px;
}
.head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.notif {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: var(--jf-space-3) var(--jf-space-1);
  border-bottom: 1px solid var(--jf-color-border);
}
.notif:last-of-type {
  border-bottom: none;
}
/* #f0f7ff：未读行的淡蓝底，是一次性语义色，tokens.css 无对应令牌。
   最接近的 --jf-color-bg-hover(#f8f9fb) 几乎看不出颜色，换上后「未读」
   与「可点击行的 hover」两种状态将无法区分，故保留字面量。
   （与 PostDetailView.vue 的 #fff8e6 同一处理方式。） */
.notif.unread {
  background: #f0f7ff;
}
.notif.clickable {
  cursor: pointer;
}
.notif.clickable:hover {
  background: var(--jf-color-bg-hover);
}
.notif-content {
  flex: 1;
  font-size: var(--jf-font-body);
}
/* #c0c4cc：时间等次要信息的浅灰，语义比 --jf-color-text-3(#909399) 更弱，
   tokens.css 未提供对应令牌，保留字面量；若将来补令牌，
   应与 PostDetailView.vue 的 .comment-time / .comment-content.deleted 一并替换。 */
.notif-time {
  color: #c0c4cc;
  font-size: var(--jf-font-small);
  flex-shrink: 0;
}
.pager {
  margin-top: var(--jf-space-4);
  justify-content: center;
}

/* 窄屏：一行要容纳类型标签、正文、时间、帖子标题标签、未读标签。
   .notif-time 设了 flex-shrink:0，不会缩；el-tag 内部是 nowrap，
   被压缩只会变成省略号；而 .notif-content 的 flex:1 等价于 flex-basis:0，
   换行算法把它当零宽，既不会为它换行，它也只能分到扣掉这些固定项后的
   余量（375px 下估算 90~140px，正文每行只剩 6~9 个汉字）。
   故允许换行（放不下的标签顺延下一行），并把正文基准宽度设为整行，
   让它独占一行，读起来更接近一整条通知。 */
@media (max-width: 768px) {
  .notif {
    flex-wrap: wrap;
  }
  .notif-content {
    flex: 1 1 100%;
  }
}
</style>
