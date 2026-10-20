<script setup>
import { computed, ref, watch } from 'vue'
import { resolveAvatarUrl } from '@/utils/avatar'

const props = defineProps({
  url: { type: String, default: '' },
  name: { type: String, default: '' },
  size: { type: Number, default: 28 }
})

// element-plus 的 el-avatar 在 src 变化时会自行把 hasLoadError 重置成 false，
// 但这里仍保留本地 failed 作为防御：不依赖内部实现，也让「换 url 后恢复图片」可直接单测。
const failed = ref(false)
const src = computed(() => resolveAvatarUrl(props.url))
const showImage = computed(() => Boolean(props.url) && !failed.value)
// Array.from 按码点切分，避免把 emoji 等代理对切成半个字符
const initial = computed(() => Array.from(props.name || '?')[0])

watch(
  () => props.url,
  () => {
    failed.value = false
  }
)
</script>

<template>
  <el-avatar
    :size="size"
    :src="showImage ? src : undefined"
    :alt="showImage ? (name ? `${name}的头像` : '用户头像') : ''"
    @error="failed = true"
  >
    <!-- 字母头像本身作为一个有名称的 img 角色；字母与 aria-label 重复，对读屏隐藏 -->
    <span role="img" :aria-label="name ? `${name}的头像` : '用户头像'">
      <span aria-hidden="true">{{ initial }}</span>
    </span>
  </el-avatar>
</template>
