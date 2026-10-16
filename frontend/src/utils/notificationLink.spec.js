import { describe, it, expect } from 'vitest'
import { resolveNotificationTarget } from './notificationLink'

describe('resolveNotificationTarget', () => {
  it('REPLY 带 postId 与 commentId 时返回帖子路径与评论锚点', () => {
    const result = resolveNotificationTarget({ type: 'REPLY', postId: 7, commentId: 31 })
    expect(result).toEqual({ path: '/posts/7', hash: '#comment-31' })
  })

  it('REPLY 缺少 commentId 时只跳帖子，不带锚点', () => {
    const result = resolveNotificationTarget({ type: 'REPLY', postId: 7, commentId: null })
    expect(result).toEqual({ path: '/posts/7', hash: '' })
  })

  it('LIKE 跳转到帖子且不带锚点', () => {
    const result = resolveNotificationTarget({ type: 'LIKE', postId: 5, commentId: null })
    expect(result).toEqual({ path: '/posts/5', hash: '' })
  })

  it('FAVORITE 跳转到帖子且不带锚点', () => {
    const result = resolveNotificationTarget({ type: 'FAVORITE', postId: 9, commentId: null })
    expect(result).toEqual({ path: '/posts/9', hash: '' })
  })

  it('SYSTEM 通知即使有 postId 也不可跳转', () => {
    expect(resolveNotificationTarget({ type: 'SYSTEM', postId: 3, commentId: null })).toBeNull()
  })

  it('未知类型不因默认而变得可跳转', () => {
    expect(resolveNotificationTarget({ type: 'MENTION', postId: 4, commentId: null })).toBeNull()
  })

  it('缺少 postId 的通知不可跳转', () => {
    expect(resolveNotificationTarget({ type: 'REPLY', postId: null, commentId: 12 })).toBeNull()
  })
})
