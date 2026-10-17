<script setup>
/**
 * 后台用户卡片：窄屏下替代表格的一行。
 *
 * 只负责展示与转发事件。「管理员不可操作」「状态标签颜色」这类业务判断
 * 由父组件通过 props 传入，组件本身不 import api、不读 store —— 这样它
 * 才能在测试里脱离 pinia 与网络独立挂载。
 */
const props = defineProps({
  user: { type: Object, required: true },
  statusTag: { type: Function, required: true },
  formatTime: { type: Function, required: true }
})

const emit = defineEmits(['status'])

/** ADMIN 不可被禁言/封禁，与表格列保持同一规则 */
const isAdminUser = (u) => u.roles?.includes('ADMIN') ?? false
</script>

<template>
  <div class="user-card">
    <div class="card-row">
      <span class="card-label">用户名</span><span>{{ props.user.username }}</span>
    </div>
    <div class="card-row">
      <span class="card-label">昵称</span><span>{{ props.user.nickname || '-' }}</span>
    </div>
    <div class="card-row">
      <span class="card-label">邮箱</span><span>{{ props.user.email }}</span>
    </div>
    <div class="card-row">
      <span class="card-label">状态</span>
      <el-tag :type="props.statusTag(props.user.status)">{{ props.user.status }}</el-tag>
    </div>
    <div class="card-row">
      <span class="card-label">禁言至</span>
      <span>{{ props.user.mutedUntil ? props.formatTime(props.user.mutedUntil) : '-' }}</span>
    </div>
    <div class="card-row">
      <span class="card-label">角色</span>
      <span>
        <el-tag v-for="r in props.user.roles" :key="r" size="small" type="info" class="role-tag">
          {{ r }}
        </el-tag>
      </span>
    </div>
    <div class="card-actions">
      <el-button
        size="small"
        type="warning"
        plain
        :disabled="isAdminUser(props.user)"
        @click="emit('status', props.user)"
      >
        状态管理
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.user-card {
  border: 1px solid var(--jf-color-border);
  border-radius: var(--jf-radius-md);
  padding: var(--jf-space-3);
  display: flex;
  flex-direction: column;
  gap: var(--jf-space-2);
}
.card-row {
  display: flex;
  gap: var(--jf-space-2);
  font-size: var(--jf-font-body);
}
.card-label {
  color: var(--jf-color-text-3);
  min-width: 64px;
  flex-shrink: 0;
}
.role-tag {
  margin-right: var(--jf-space-1);
}
.card-actions {
  display: flex;
  gap: var(--jf-space-2);
  margin-top: var(--jf-space-1);
}
</style>
