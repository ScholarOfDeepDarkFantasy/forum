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

  it('子路径部署（/forum/）拼出 /forum/uploads/...（固定基址，避免用同一个 BASE_URL 自证）', () => {
    expect(resolveAvatarUrl('/uploads/avatars/x.png', '/forum/')).toBe('/forum/uploads/avatars/x.png')
  })

  it('根路径部署（/）拼出 /uploads/...', () => {
    expect(resolveAvatarUrl('/uploads/avatars/x.png', '/')).toBe('/uploads/avatars/x.png')
  })

  it('首尾空白先裁掉，避免拼出带空格的 404 地址', () => {
    expect(resolveAvatarUrl(' /uploads/x.jpg ', '/forum/')).toBe('/forum/uploads/x.jpg')
    expect(resolveAvatarUrl('   ', '/forum/')).toBe('')
  })

  it('只有 /uploads 或 /uploads/ 而没有文件段时返回空串，且不误伤 /uploadsx', () => {
    expect(resolveAvatarUrl('/uploads', '/forum/')).toBe('')
    expect(resolveAvatarUrl('/uploads/', '/forum/')).toBe('')
    expect(resolveAvatarUrl('/uploadsx/a.png', '/forum/')).toBe('/uploadsx/a.png')
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
