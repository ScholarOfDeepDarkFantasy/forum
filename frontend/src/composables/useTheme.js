import { computed, readonly, ref, watchEffect } from 'vue'

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

// 对外只读：setMode 是唯一写入口，避免组件直接改 mode 后与 localStorage 脱节。
const readonlyMode = readonly(mode)

let listenerBound = false

function onSystemChange(event) {
  systemPrefersDark.value = event.matches
}

// Safari < 14 的 MediaQueryList 只有 addListener/removeListener，
// 直接调用 addEventListener 会在模块初始化时抛错并白屏；与 useResponsive 一样先做能力检测。
function bindSystemListener() {
  if (typeof query.addEventListener === 'function') {
    query.addEventListener('change', onSystemChange)
  } else if (typeof query.addListener === 'function') {
    query.addListener(onSystemChange)
  }
}

function unbindSystemListener() {
  if (typeof query.removeEventListener === 'function') {
    query.removeEventListener('change', onSystemChange)
  } else if (typeof query.removeListener === 'function') {
    query.removeListener(onSystemChange)
  }
}

function syncSystemListener() {
  if (!query) return
  if (mode.value === 'system') {
    // 显式模式期间监听是解绑的，OS 主题可能已经变过；重新绑定前先取当前值，
    // 否则要等下一次系统事件才纠正（首次进入时也不过是一次无害赋值）。
    systemPrefersDark.value = query.matches
    if (!listenerBound) {
      bindSystemListener()
      listenerBound = true
    }
  } else if (listenerBound) {
    unbindSystemListener()
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
  // 顺序有意义：先同步监听并刷新系统偏好，再读 isDark，
  // 否则切回 system 的那一次渲染仍会用旧偏好上类（晚一个 tick）。
  syncSystemListener()
  applyClass(isDark.value)
})

export function initTheme() {
  applyClass(isDark.value)
  return isDark.value
}

export function useTheme() {
  return { mode: readonlyMode, isDark, systemPrefersDark, setMode, toggle, initTheme }
}
