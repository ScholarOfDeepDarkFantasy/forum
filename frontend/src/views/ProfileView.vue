<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { postApi, userApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/format'
import UserAvatar from '@/components/UserAvatar.vue'

const router = useRouter()
const auth = useAuthStore()

const tab = ref('profile')
const profileForm = reactive({
  nickname: '',
  bio: ''
})
const savingProfile = ref(false)

const avatarInput = ref(null)
const uploadingAvatar = ref(false)
const removingAvatar = ref(false)

const AVATAR_MAX_BYTES = 2 * 1024 * 1024
const AVATAR_TYPES = ['image/jpeg', 'image/png']
const AVATAR_EXTENSIONS = ['.jpg', '.jpeg', '.png']

/**
 * 图片类型优先看 MIME；某些环境（拖拽、旧浏览器、部分移动端）给出的 file.type 为空，
 * 此时按扩展名回退。真正的图片内容由后端按 magic bytes 校验，前端这一步只做早筛。
 */
function isAvatarFile(file) {
  if (file.type) return AVATAR_TYPES.includes(file.type)
  const filename = (file.name || '').toLowerCase()
  return AVATAR_EXTENSIONS.some((ext) => filename.endsWith(ext))
}

const myPosts = ref({ content: [], totalElements: 0, totalPages: 0 })
const myPostPage = ref(0)
// 后端返回的是分页对象 { content, totalElements, totalPages }，不是数组。
// 早先这里声明成 ref([]) 并把整个分页对象赋进去，模板读 myFavorites.length 恒为 undefined，
// 于是收藏成功后永远显示「还没有收藏」。
const myFavorites = ref({ content: [], totalElements: 0, totalPages: 0 })
const myFavoritePage = ref(0)

async function loadProfile() {
  profileForm.nickname = auth.user?.nickname || ''
  profileForm.bio = auth.user?.bio || ''
}

async function saveProfile() {
  savingProfile.value = true
  try {
    await auth.updateProfile({
      nickname: profileForm.nickname || undefined,
      bio: profileForm.bio || undefined
    })
    ElMessage.success('资料已保存')
  } finally {
    savingProfile.value = false
  }
}

function pickAvatar() {
  avatarInput.value?.click()
}

async function onAvatarChange(event) {
  const file = event.target.files?.[0]
  // 清空 value，允许连续选择同一个文件
  event.target.value = ''
  if (!file) return
  if (!isAvatarFile(file)) {
    ElMessage.warning('仅支持 JPEG/PNG 图片')
    return
  }
  if (file.size > AVATAR_MAX_BYTES) {
    ElMessage.warning('图片不能超过 2MB')
    return
  }
  uploadingAvatar.value = true
  try {
    await userApi.uploadAvatar(file)
    await auth.fetchMe()
    ElMessage.success('头像已更新')
  } catch {
    // 错误已由 axios 拦截器统一提示，这里只负责复位状态，避免二次 toast
  } finally {
    uploadingAvatar.value = false
  }
}

async function removeAvatar() {
  removingAvatar.value = true
  try {
    await userApi.removeAvatar()
    await auth.fetchMe()
    ElMessage.success('头像已移除')
  } catch {
    // 同上：错误已由拦截器统一提示
  } finally {
    removingAvatar.value = false
  }
}

async function loadMyPosts(page = 0) {
  myPosts.value = await postApi.mine({ page, size: 10 })
  myPostPage.value = page
}

async function loadFavorites(page = 0) {
  try {
    myFavorites.value = await postApi.myFavorites({ page, size: 10 })
    myFavoritePage.value = page
  } catch {
    myFavorites.value = { content: [], totalElements: 0, totalPages: 0 }
  }
}

async function removePost(p) {
  await postApi.remove(p.id)
  ElMessage.success('已删除')
  await loadMyPosts(myPostPage.value)
}

onMounted(() => {
  loadProfile()
  loadMyPosts()
  loadFavorites()
})
</script>

<template>
  <el-card shadow="never" class="profile-card">
    <el-tabs v-model="tab">
      <!-- 个人资料 -->
      <el-tab-pane label="个人资料" name="profile">
        <el-form label-position="top" style="max-width: 480px">
          <el-form-item label="用户名">
            <el-input :model-value="auth.user?.username" disabled />
          </el-form-item>
          <el-form-item label="昵称">
            <el-input v-model="profileForm.nickname" placeholder="昵称" />
          </el-form-item>
          <el-form-item label="头像">
            <div class="avatar-block">
              <UserAvatar
                :url="auth.user?.avatarUrl"
                :name="auth.user?.nickname || auth.user?.username"
                :size="64"
              />
              <div class="avatar-actions">
                <el-button :loading="uploadingAvatar" :disabled="removingAvatar" @click="pickAvatar">选择图片</el-button>
                <el-button
                  v-if="auth.user?.avatarUrl"
                  class="avatar-remove"
                  text
                  type="danger"
                  :loading="removingAvatar"
                  :disabled="uploadingAvatar"
                  @click="removeAvatar"
                >移除头像</el-button>
              </div>
              <input
                ref="avatarInput"
                class="avatar-input"
                type="file"
                accept="image/jpeg,image/png"
                @change="onAvatarChange"
              />
              <p class="avatar-hint">支持 JPEG/PNG，不超过 2MB；服务端会居中裁剪为 256×256。</p>
            </div>
          </el-form-item>
          <el-form-item label="个人简介">
            <el-input v-model="profileForm.bio" type="textarea" :rows="3" placeholder="介绍一下自己…" />
          </el-form-item>
          <el-button type="primary" :loading="savingProfile" @click="saveProfile">保存资料</el-button>
        </el-form>
      </el-tab-pane>

      <!-- 我的帖子 -->
      <el-tab-pane label="我的帖子" name="posts">
        <template v-if="myPosts.content.length">
          <div v-for="p in myPosts.content" :key="p.id" class="row-item" @click="router.push(`/posts/${p.id}`)">
            <span class="row-title">{{ p.title }}</span>
            <span class="row-meta">{{ formatDateTime(p.createTime) }} · 评论 {{ p.commentCount }} · 赞 {{ p.likeCount }}</span>
            <el-button size="small" type="danger" plain @click.stop="removePost(p)">删除</el-button>
          </div>
          <el-pagination
            v-if="myPosts.totalPages > 1"
            layout="prev, pager, next"
            :total="myPosts.totalElements"
            :page-size="10"
            :current-page="myPostPage + 1"
            class="jf-pager"
            @current-change="(p) => loadMyPosts(p - 1)"
          />
        </template>
        <el-empty v-else description="还没有发过帖子" />
      </el-tab-pane>

      <!-- 我的收藏 -->
      <el-tab-pane label="我的收藏" name="favorites">
        <template v-if="myFavorites.content.length">
          <div v-for="p in myFavorites.content" :key="p.id" class="row-item" @click="router.push(`/posts/${p.id}`)">
            <span class="row-title">{{ p.title }}</span>
            <span class="row-meta">{{ p.boardName }} · {{ formatDateTime(p.createTime) }}</span>
          </div>
          <el-pagination
            v-if="myFavorites.totalPages > 1"
            layout="prev, pager, next"
            :total="myFavorites.totalElements"
            :page-size="10"
            :current-page="myFavoritePage + 1"
            class="jf-pager"
            @current-change="(p) => loadFavorites(p - 1)"
          />
        </template>
        <el-empty v-else description="还没有收藏" />
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<style scoped>
.profile-card {
  margin: var(--jf-space-4) auto;
  max-width: 860px;
}
.row-item {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: var(--jf-space-3) var(--jf-space-1);
  border-bottom: 1px solid var(--jf-color-border);
  cursor: pointer;
}
.row-item:hover {
  background: var(--jf-color-bg-hover);
}
.row-item:last-of-type {
  border-bottom: none;
}
.row-title {
  flex: 1;
  font-size: 15px;
}
.row-meta {
  color: var(--jf-color-text-3);
  font-size: var(--jf-font-meta);
  flex-shrink: 0;
}
.avatar-block {
  display: flex;
  flex-direction: column;
  gap: var(--jf-space-2);
}
.avatar-actions {
  display: flex;
  align-items: center;
  gap: var(--jf-space-2);
}
.avatar-input {
  display: none;
}
.avatar-hint {
  margin: 0;
  color: var(--jf-color-text-3);
  font-size: var(--jf-font-small);
}
</style>
