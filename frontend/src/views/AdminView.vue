<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { boardApi, adminApi } from '@/api'
import { useResponsive } from '@/composables/useResponsive'
import { formatDateTime } from '@/utils/format'
import UserCard from '@/components/UserCard.vue'
import BoardCard from '@/components/BoardCard.vue'

// 表格与卡片的 DOM 结构不同，媒体查询无法切换模板，只能由 JS 判定
const { isMobile } = useResponsive()

const activeTab = ref('boards')

// ===== 板块管理 =====
const boards = ref([])
const boardDialog = ref(false)
const boardEditing = ref(null) // null=新建，否则为编辑目标
const boardForm = reactive({ name: '', description: '', sortOrder: 0 })

async function loadBoards() {
  boards.value = await boardApi.list()
}

function openBoardCreate() {
  boardEditing.value = null
  boardForm.name = ''
  boardForm.description = ''
  boardForm.sortOrder = 0
  boardDialog.value = true
}

function openBoardEdit(b) {
  boardEditing.value = b
  boardForm.name = b.name
  boardForm.description = b.description || ''
  boardForm.sortOrder = b.sortOrder ?? 0
  boardDialog.value = true
}

async function saveBoard() {
  if (!boardForm.name.trim()) {
    ElMessage.warning('请输入板块名')
    return
  }
  const payload = { name: boardForm.name, description: boardForm.description || undefined, sortOrder: boardForm.sortOrder }
  if (boardEditing.value) {
    await boardApi.update(boardEditing.value.id, payload)
    ElMessage.success('板块已更新')
  } else {
    await boardApi.create(payload)
    ElMessage.success('板块已创建')
  }
  boardDialog.value = false
  await loadBoards()
}

async function removeBoard(b) {
  await ElMessageBox.confirm(`确定删除板块「${b.name}」吗？（板块下有帖子时会被拒绝）`, '提示', {
    type: 'warning',
    // 第九轮：按需引入后 ElMessageBox 拿不到 <el-config-provider>，显式给中文按钮文案
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    distinguishCancelAndClose: true
  })
  await boardApi.remove(b.id)
  ElMessage.success('已删除')
  await loadBoards()
}

// ===== 用户管理 =====
const users = ref({ content: [], totalElements: 0, totalPages: 0 })
const userPage = ref(0)
const statusDialog = ref(false)
const statusTarget = ref(null)
const statusForm = reactive({ status: 'ACTIVE', muteMinutes: 60 })

async function loadUsers(p = 0) {
  users.value = await adminApi.users({ page: p, size: 10 })
  userPage.value = p
}

function openStatus(u) {
  statusTarget.value = u
  statusForm.status = u.status || 'ACTIVE'
  statusForm.muteMinutes = 60
  statusDialog.value = true
}

async function saveStatus() {
  const payload = { status: statusForm.status }
  if (statusForm.status === 'MUTED') {
    payload.muteMinutes = statusForm.muteMinutes
  }
  await adminApi.changeStatus(statusTarget.value.id, payload)
  ElMessage.success('操作成功')
  statusDialog.value = false
  await loadUsers(userPage.value)
}

function statusTag(s) {
  return { ACTIVE: 'success', MUTED: 'warning', BANNED: 'danger' }[s] || 'info'
}

onMounted(() => {
  loadBoards()
  loadUsers(0)
})
</script>

<template>
  <el-card shadow="never" class="admin-card">
    <el-tabs v-model="activeTab">
      <!-- 板块管理 -->
      <el-tab-pane label="板块管理" name="boards">
        <el-button type="primary" class="mb" @click="openBoardCreate">新建板块</el-button>
        <div v-if="isMobile" class="board-cards">
          <el-empty v-if="!boards.length" description="暂无板块" />
          <template v-else>
            <BoardCard
              v-for="b in boards"
              :key="b.id"
              :board="b"
              @edit="openBoardEdit"
              @remove="removeBoard"
            />
          </template>
        </div>
        <el-table v-else :data="boards" stripe>
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="name" label="名称" min-width="140" />
          <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
          <el-table-column prop="sortOrder" label="排序" width="80" />
          <el-table-column prop="postCount" label="帖子数" width="90" />
          <el-table-column label="操作" width="160">
            <template #default="{ row }">
              <el-button size="small" @click="openBoardEdit(row)">编辑</el-button>
              <el-button size="small" type="danger" plain @click="removeBoard(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 用户管理 -->
      <el-tab-pane label="用户管理" name="users">
        <div v-if="isMobile" class="user-cards">
          <el-empty v-if="!users.content.length" description="暂无用户" />
          <template v-else>
            <UserCard
              v-for="u in users.content"
              :key="u.id"
              :user="u"
              :status-tag="statusTag"
              :format-time="formatDateTime"
              @status="openStatus"
            />
          </template>
        </div>
        <el-table v-else :data="users.content" stripe>
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="username" label="用户名" min-width="120" />
          <el-table-column prop="email" label="邮箱" min-width="180" show-overflow-tooltip />
          <el-table-column prop="nickname" label="昵称" min-width="110" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="statusTag(row.status)">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="禁言至" width="160">
            <template #default="{ row }">{{ row.mutedUntil ? formatDateTime(row.mutedUntil) : '-' }}</template>
          </el-table-column>
          <el-table-column label="角色" width="180">
            <template #default="{ row }">
              <el-tag v-for="r in row.roles" :key="r" size="small" type="info" class="role-tag">{{ r }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button size="small" type="warning" plain :disabled="row.roles?.includes('ADMIN')" @click="openStatus(row)">
                状态管理
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-if="users.totalPages > 1"
          layout="prev, pager, next"
          :total="users.totalElements"
          :page-size="10"
          :current-page="userPage + 1"
          class="jf-pager"
          @current-change="(p) => loadUsers(p - 1)"
        />
      </el-tab-pane>
    </el-tabs>

    <!-- 板块编辑对话框 -->
    <el-dialog v-model="boardDialog" :title="boardEditing ? '编辑板块' : '新建板块'" width="440px">
      <el-form label-position="top">
        <el-form-item label="板块名" required>
          <el-input v-model="boardForm.name" maxlength="50" placeholder="板块名" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="boardForm.description" type="textarea" :rows="2" maxlength="255" />
        </el-form-item>
        <el-form-item label="排序值（越小越靠前）">
          <el-input-number v-model="boardForm.sortOrder" :min="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="boardDialog = false">取消</el-button>
        <el-button type="primary" @click="saveBoard">保存</el-button>
      </template>
    </el-dialog>

    <!-- 用户状态对话框 -->
    <el-dialog v-model="statusDialog" :title="`管理用户：${statusTarget?.username}`" width="420px">
      <el-form label-position="top">
        <el-form-item label="目标状态">
          <el-radio-group v-model="statusForm.status">
            <el-radio value="ACTIVE">正常</el-radio>
            <el-radio value="MUTED">禁言</el-radio>
            <el-radio value="BANNED">封禁</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="statusForm.status === 'MUTED'" label="禁言时长（分钟）">
          <el-input-number v-model="statusForm.muteMinutes" :min="1" :max="60 * 24 * 30" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="statusDialog = false">取消</el-button>
        <el-button type="primary" @click="saveStatus">确认</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.admin-card {
  margin: var(--jf-space-4) auto;
  max-width: 1100px;
}
.mb {
  margin-bottom: var(--jf-space-3);
}
.role-tag {
  margin-right: var(--jf-space-1);
}

/* 窄屏卡片容器：卡片本身与字段样式在 components/UserCard.vue、BoardCard.vue 里 */
.user-cards,
.board-cards {
  display: flex;
  flex-direction: column;
  gap: var(--jf-space-3);
}
</style>
