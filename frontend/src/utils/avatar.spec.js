import { describe, it, expect } from 'vitest'
import { resolveAvatarUrl } from './avatar.js'

describe('resolveAvatarUrl', () => {
  it('空值返回空串', () => {
    expect(resolveAvatarUrl('')).toBe('')
    expect(resolveAvatarUrl(null)).toBe('')
    expect(resolveAvatarUrl(undefined)).toBe('')
  })

  it('/uploads/ 前缀拼上 BASE_URL', () => {
    expect(resolveAvatarUrl('/uploads/avatars/a.jpg')).toBe(
      `${import.meta.env.BASE_URL}uploads/avatars/a.jpg`
    )
  })

  it('外链 http(s) 原样返回', () => {
    expect(resolveAvatarUrl('https://cdn.example.com/a.png')).toBe('https://cdn.example.com/a.png')
    expect(resolveAvatarUrl('http://cdn.example.com/a.png')).toBe('http://cdn.example.com/a.png')
  })

  it('data URL 原样返回', () => {
    const dataUrl = 'data:image/png;base64,iVBORw0KGgo='
    expect(resolveAvatarUrl(dataUrl)).toBe(dataUrl)
  })

  it('其它站内路径原样返回', () => {
    expect(resolveAvatarUrl('/assets/a.png')).toBe('/assets/a.png')
  })
})
