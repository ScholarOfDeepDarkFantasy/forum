import { computed, ref, watchEffect } from 'vue'

export const STORAGE_KEY = 'jf_theme'
const VALID_MODES = ['system', 'light', 'dark']

function readStoredMode() {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    return saved === 'light' || saved === 'dark' ? saved : 'system'
  } catch {
    return 'system'
  }
}

function darkMediaQuery() {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return null
  return window.matchMedia('(prefers-color-scheme: dark)')
}

// 模块级单例：组件、路由守卫与入口 initTheme 共用同一份状态。
const mode = ref(readStoredMode())
const query = darkMediaQuery()
const systemPrefersDark = ref(query ? query.matches : false)
const isDark = computed(
  () => mode.value === 'dark' || (mode.value === 'system' && systemPrefersDark.value)
)

let listenerBound = false

function onSystemChange(event) {
  systemPrefersDark.value = event.matches
}

function syncSystemListener() {
  if (!query) return
  if (mode.value === 'system' && !listenerBound) {
    query.addEventListener('change', onSystemChange)
    listenerBound = true
  } else if (mode.value !== 'system' && listenerBound) {
    query.removeEventListener('change', onSystemChange)
    listenerBound = false
  }
}

function applyClass(dark) {
  if (typeof document === 'undefined') return
  const root = document.documentElement
  root.classList.toggle('dark', dark)
  root.style.colorScheme = dark ? 'dark' : 'light'
}

function persist(next) {
  try {
    if (next === 'system') localStorage.removeItem(STORAGE_KEY)
    else localStorage.setItem(STORAGE_KEY, next)
  } catch {
    // 隐私模式 / localStorage 被禁用：忽略，仅当次会话生效
  }
}

function setMode(next) {
  mode.value = VALID_MODES.includes(next) ? next : 'system'
  persist(mode.value)
}

function toggle() {
  setMode(isDark.value ? 'light' : 'dark')
}

watchEffect(() => {
  applyClass(isDark.value)
  syncSystemListener()
})

export function initTheme() {
  applyClass(isDark.value)
  return isDark.value
}

export function useTheme() {
  return { mode, isDark, systemPrefersDark, setMode, toggle, initTheme }
}
