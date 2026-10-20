<script setup>
import { computed } from 'vue'
import { Check, Moon, MoonNight, Sunny } from '@element-plus/icons-vue'
import { useTheme } from '@/composables/useTheme'

const { mode, setMode } = useTheme()

const MODE_ITEMS = [
  { value: 'system', label: '跟随系统', icon: MoonNight },
  { value: 'light', label: '浅色', icon: Sunny },
  { value: 'dark', label: '深色', icon: Moon }
]

const currentIcon = computed(
  () => (MODE_ITEMS.find((item) => item.value === mode.value) || MODE_ITEMS[0]).icon
)
const currentLabel = computed(
  () => (MODE_ITEMS.find((item) => item.value === mode.value) || MODE_ITEMS[0]).label
)

function handleCommand(command) {
  setMode(command)
}
</script>

<template>
  <el-dropdown trigger="click" @command="handleCommand">
    <el-button
      circle
      text
      :icon="currentIcon"
      aria-label="切换主题"
      :title="`主题：${currentLabel}`"
    />
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item v-for="item in MODE_ITEMS" :key="item.value" :command="item.value">
          <span class="theme-item" :class="{ 'is-active': mode === item.value }">
            <el-icon>
              <Check v-if="mode === item.value" />
              <component :is="item.icon" v-else />
            </el-icon>
            <span class="theme-label">{{ item.label }}</span>
          </span>
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<style scoped>
.theme-item {
  display: flex;
  align-items: center;
  gap: var(--jf-space-2);
}
.theme-label {
  font-size: var(--jf-font-body);
}
</style>
