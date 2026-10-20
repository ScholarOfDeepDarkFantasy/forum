<script setup>
import { computed, ref, watch } from 'vue'
import { resolveAvatarUrl } from '@/utils/avatar'

const props = defineProps({
  url: { type: String, default: '' },
  name: { type: String, default: '' },
  size: { type: Number, default: 28 }
})

// 图片 404 / 损坏时 el-avatar 不会自动消失，用 @error 切回字母头像
const failed = ref(false)
const src = computed(() => resolveAvatarUrl(props.url))
const showImage = computed(() => Boolean(props.url) && !failed.value)
const initial = computed(() => (props.name || '?').slice(0, 1))

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
    :alt="showImage ? name || '用户头像' : ''"
    @error="failed = true"
  >
    {{ initial }}
  </el-avatar>
</template>
