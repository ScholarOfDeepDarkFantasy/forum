import { describe, it, expect, beforeEach, vi } from 'vitest'
import { nextTick } from 'vue'

const STORAGE_KEY = 'jf_theme'
let changeHandlers = []
let systemPrefersDark = false

function installMatchMedia(prefersDark) {
  systemPrefersDark = prefersDark
  changeHandlers = []
  window.matchMedia = (query) => ({
    get matches() {
      return systemPrefersDark
    },
    media: query,
    onchange: null,
    addEventListener: (type, handler) => {
      if (type === 'change') changeHandlers.push(handler)
    },
    removeEventListener: (type, handler) => {
      changeHandlers = changeHandlers.filter((item) => item !== handler)
    },
    addListener: (handler) => changeHandlers.push(handler),
    removeListener: (handler) => {
      changeHandlers = changeHandlers.filter((item) => item !== handler)
    },
    dispatchEvent: () => false
  })
}

function emitSystemChange(prefersDark) {
  systemPrefersDark = prefersDark
  for (const handler of [...changeHandlers]) handler({ matches: prefersDark })
}

async function loadTheme() {
  vi.resetModules()
  const mod = await import('./useTheme.js')
  return mod.useTheme()
}

beforeEach(() => {
  localStorage.clear()
  document.documentElement.className = ''
  document.documentElement.style.colorScheme = ''
  installMatchMedia(false)
})

describe('useTheme', () => {
  it('默认跟随系统，isDark 取系统值', async () => {
    const theme = await loadTheme()
    expect(theme.mode.value).toBe('system')
    expect(theme.isDark.value).toBe(false)
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })

  it('system 模式下系统主题变化实时同步 html 类', async () => {
    const theme = await loadTheme()
    emitSystemChange(true)
    await nextTick()
    expect(theme.isDark.value).toBe(true)
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })

  it('setMode("dark") 写 localStorage、挂 dark 类并同步 colorScheme', async () => {
    const theme = await loadTheme()
    theme.setMode('dark')
    await nextTick()
    expect(localStorage.getItem(STORAGE_KEY)).toBe('dark')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    expect(document.documentElement.style.colorScheme).toBe('dark')
  })

  it('setMode("light") 在系统为暗时仍是亮色', async () => {
    installMatchMedia(true)
    const theme = await loadTheme()
    theme.setMode('light')
    await nextTick()
    expect(localStorage.getItem(STORAGE_KEY)).toBe('light')
    expect(document.documentElement.classList.contains('dark')).toBe(false)
    expect(document.documentElement.style.colorScheme).toBe('light')
  })

  it('localStorage 坏值回退 system', async () => {
    localStorage.setItem(STORAGE_KEY, 'purple')
    const theme = await loadTheme()
    expect(theme.mode.value).toBe('system')
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })

  it('已保存 dark 时导入即应用 dark', async () => {
    localStorage.setItem(STORAGE_KEY, 'dark')
    const theme = await loadTheme()
    expect(theme.mode.value).toBe('dark')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })

  it('选择跟随系统时删除 localStorage 键', async () => {
    const theme = await loadTheme()
    theme.setMode('dark')
    theme.setMode('system')
    await nextTick()
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull()
  })

  it('toggle 在当前实际效果上取反', async () => {
    installMatchMedia(true)
    const theme = await loadTheme()
    expect(theme.isDark.value).toBe(true)
    theme.toggle()
    await nextTick()
    expect(theme.mode.value).toBe('light')
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })

  it('matchMedia 缺失时按 false 处理且不抛错', async () => {
    delete window.matchMedia
    const theme = await loadTheme()
    expect(theme.mode.value).toBe('system')
    expect(theme.isDark.value).toBe(false)
  })

  it('切到显式模式后不再监听系统变化', async () => {
    const theme = await loadTheme()
    expect(changeHandlers.length).toBe(1)
    theme.setMode('dark')
    await nextTick()
    expect(changeHandlers.length).toBe(0)
  })
})
