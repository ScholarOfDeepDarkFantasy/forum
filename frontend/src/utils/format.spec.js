import { describe, it, expect } from 'vitest'
import { formatDateTime, formatRelative } from './format'

describe('formatDateTime', () => {
  it('把后端 ISO 字符串格式化为到分钟的本地文本', () => {
    expect(formatDateTime('2026-10-17T10:23:34.827384')).toBe('2026-10-17 10:23')
  })

  it('空值返回空串', () => {
    expect(formatDateTime(null)).toBe('')
    expect(formatDateTime(undefined)).toBe('')
    expect(formatDateTime('')).toBe('')
  })
})

describe('formatRelative', () => {
  const now = new Date('2026-10-17T12:00:00').getTime()

  it('一分钟内显示刚刚', () => {
    expect(formatRelative('2026-10-17T11:59:30', now)).toBe('刚刚')
  })

  it('一小时内显示分钟数', () => {
    expect(formatRelative('2026-10-17T11:30:00', now)).toBe('30 分钟前')
  })

  it('一天内显示小时数', () => {
    expect(formatRelative('2026-10-17T09:00:00', now)).toBe('3 小时前')
  })

  it('超过一天回退到具体日期', () => {
    expect(formatRelative('2026-10-14T09:00:00', now)).toBe('2026-10-14 09:00')
  })

  it('空值返回空串', () => {
    expect(formatRelative(null, now)).toBe('')
  })

  it('无法解析的字符串回退到原样截取，不抛异常', () => {
    expect(formatRelative('not-a-date', now)).toBe('not-a-date')
  })

  it('时间在未来（时钟偏差）时显示刚刚，不出现负数', () => {
    expect(formatRelative('2026-10-17T12:05:00', now)).toBe('刚刚')
  })
})
