import { describe, it, expect, beforeEach, vi } from 'vitest'
import { nextTick } from 'vue'
// 存储键从实现导入：改名时这里与 index.html 内联脚本必须一起失败，而不是各自绿。
import { STORAGE_KEY } from './useTheme.js'

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

// Safari < 14 的 MediaQueryList 只有 addListener/removeListener。
function installLegacyMatchMedia(prefersDark) {
  systemPrefersDark = prefersDark
  changeHandlers = []
  window.matchMedia = (query) => ({
    get matches() {
      return systemPrefersDark
    },
    media: query,
    onchange: null,
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

  it('system 模式下系统由暗转亮也实时同步', async () => {
    installMatchMedia(true)
    const theme = await loadTheme()
    expect(theme.isDark.value).toBe(true)
    emitSystemChange(false)
    await nextTick()
    expect(theme.isDark.value).toBe(false)
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })

  it('显式模式期间系统变化，切回 system 时读取最新偏好（不依赖事件）', async () => {
    const theme = await loadTheme()
    theme.setMode('dark')
    await nextTick()
    expect(changeHandlers.length).toBe(0)

    // 模拟监听解绑期间 OS 切到深色：没有 handler，只改变 query.matches。
    systemPrefersDark = true

    theme.setMode('system')
    await nextTick()
    expect(theme.isDark.value).toBe(true)
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })

  it('仅支持 addListener/removeListener 的旧环境可绑定与解绑', async () => {
    installLegacyMatchMedia(false)
    const theme = await loadTheme()
    expect(changeHandlers.length).toBe(1)

    theme.setMode('dark')
    await nextTick()
    expect(changeHandlers.length).toBe(0)

    theme.setMode('system')
    await nextTick()
    expect(changeHandlers.length).toBe(1)

    emitSystemChange(true)
    await nextTick()
    expect(theme.isDark.value).toBe(true)
  })

  it('localStorage.getItem 抛错时仍按 system 初始化', async () => {
    const spy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('storage disabled')
    })
    try {
      const theme = await loadTheme()
      expect(theme.mode.value).toBe('system')
      expect(theme.isDark.value).toBe(false)
    } finally {
      spy.mockRestore()
    }
  })

  it('localStorage.setItem 抛错时切换仍生效且不抛错', async () => {
    const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota exceeded')
    })
    try {
      const theme = await loadTheme()
      expect(() => theme.setMode('dark')).not.toThrow()
      await nextTick()
      expect(theme.mode.value).toBe('dark')
      expect(document.documentElement.classList.contains('dark')).toBe(true)
      expect(document.documentElement.style.colorScheme).toBe('dark')
    } finally {
      spy.mockRestore()
    }
  })

  it('initTheme 幂等、返回当前生效主题，且 Storage 抛错时安全', async () => {
    const spy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('storage disabled')
    })
    try {
      vi.resetModules()
      const mod = await import('./useTheme.js')
      const first = mod.initTheme()
      const second = mod.initTheme()
      expect(first).toBe(false)
      expect(second).toBe(first)
      expect(document.documentElement.classList.contains('dark')).toBe(false)
      expect(document.documentElement.style.colorScheme).toBe('light')
    } finally {
      spy.mockRestore()
    }
  })

  it('initTheme 在已保存 dark 时返回 true', async () => {
    localStorage.setItem(STORAGE_KEY, 'dark')
    vi.resetModules()
    const mod = await import('./useTheme.js')
    expect(mod.initTheme()).toBe(true)
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })
})
