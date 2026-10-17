<script setup>
/**
 * 后台板块卡片：窄屏下替代表格的一行。
 *
 * 编辑/删除只发事件，由父组件决定行为（弹窗、确认框、请求）。
 * 删除前的二次确认留在父组件，组件不碰 ElMessageBox。
 */
const props = defineProps({
  board: { type: Object, required: true }
})

const emit = defineEmits(['edit', 'remove'])
</script>

<template>
  <div class="board-card">
    <div class="card-row">
      <span class="card-label">名称</span><span>{{ props.board.name }}</span>
    </div>
    <div class="card-row">
      <span class="card-label">描述</span><span>{{ props.board.description || '-' }}</span>
    </div>
    <div class="card-row">
      <span class="card-label">帖子数</span><span>{{ props.board.postCount }}</span>
    </div>
    <div class="card-actions">
      <el-button size="small" @click="emit('edit', props.board)">编辑</el-button>
      <el-button size="small" type="danger" plain @click="emit('remove', props.board)">删除</el-button>
    </div>
  </div>
</template>

<style scoped>
.board-card {
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
.card-actions {
  display: flex;
  gap: var(--jf-space-2);
  margin-top: var(--jf-space-1);
}
</style>
