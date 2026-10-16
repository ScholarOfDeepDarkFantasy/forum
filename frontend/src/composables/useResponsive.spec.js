import { describe, it, expect, vi, beforeEach } from 'vitest'

function mockMatchMedia(matches) {
  window.matchMedia = vi.fn().mockImplementation((query) => ({
    matches,
    media: query,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn()
  }))
}

import { useResponsive } from './useResponsive'

describe('useResponsive', () => {
  beforeEach(() => vi.resetModules())

  it('窄屏时 isMobile 为 true', async () => {
    mockMatchMedia(true)
    const { useResponsive: fresh } = await import('./useResponsive')
    expect(fresh().isMobile.value).toBe(true)
  })

  it('宽屏时 isMobile 为 false', () => {
    mockMatchMedia(false)
    const { isMobile } = useResponsive()
    expect(isMobile.value).toBe(false)
  })

  it('查询字符串使用 768px 断点', () => {
    mockMatchMedia(false)
    useResponsive()
    expect(window.matchMedia).toHaveBeenCalledWith('(max-width: 768px)')
  })
})
