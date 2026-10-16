import { ref, onMounted, onUnmounted } from 'vue'

// 与 tokens.css 的 --jf-breakpoint-mobile 保持一致，改断点须与各 @media 同步
export const MOBILE_BREAKPOINT = 768
const QUERY = `(max-width: ${MOBILE_BREAKPOINT}px)`

/**
 * 响应式判定：仅用于必须切换 DOM 结构的场景（如后台表格 vs 卡片）。
 * 纯样式差异优先用 CSS 媒体查询解决。
 */
export function useResponsive() {
  const isMobile = ref(
    typeof window !== 'undefined' && window.matchMedia
      ? window.matchMedia(QUERY).matches
      : false
  )

  let mql = null
  const handler = (e) => {
    isMobile.value = e.matches
  }

  onMounted(() => {
    if (typeof window === 'undefined' || !window.matchMedia) return
    mql = window.matchMedia(QUERY)
    isMobile.value = mql.matches
    if (mql.addEventListener) mql.addEventListener('change', handler)
  })

  onUnmounted(() => {
    if (mql && mql.removeEventListener) mql.removeEventListener('change', handler)
  })

  return { isMobile }
}
