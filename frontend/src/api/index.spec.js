import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

/**
 * 拦截器是「登录失败提示错了」与「会话失效跳登录页」这两件事的唯一交汇点，
 * 所以这里直接针对拦截器行为做断言，而不是绕道组件。
 *
 * axios 实例在模块加载时就创建了，因此必须在 import 之前把 axios 换成假的：
 * 假实例只保留 interceptors 槽位，把注册进来的处理函数抓出来手工调用。
 */
const handlers = { request: null, requestError: null, response: null, responseError: null }

vi.mock('axios', () => {
  const fakeInstance = {
    interceptors: {
      request: {
        use: (onFulfilled, onRejected) => {
          handlers.request = onFulfilled
          handlers.requestError = onRejected
        }
      },
      response: {
        use: (onFulfilled, onRejected) => {
          handlers.response = onFulfilled
          handlers.responseError = onRejected
        }
      }
    }
  }
  return { default: { create: () => fakeInstance } }
})

const elMessageError = vi.fn()
vi.mock('element-plus', () => ({ ElMessage: { error: (...a) => elMessageError(...a) } }))

// 动态 import：确保上面的 mock 在模块求值前已生效
await import('./index.js')

/** 构造一个 axios 错误对象，模拟后端返回 Result.fail 的响应体 */
function httpError(status, message, url) {
  return {
    config: { url },
    response: { status, data: { success: false, message } },
    message: 'Request failed'
  }
}

describe('api 拦截器', () => {
  let originalLocation

  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    originalLocation = window.location
    // jsdom 的 location.href 赋值会触发导航，这里换成可观测的桩
    delete window.location
    window.location = { pathname: '/', href: '/' }
  })

  afterEach(() => {
    window.location = originalLocation
  })

  it('登录接口 401 原样展示后端消息，且不清 token、不跳转', async () => {
    localStorage.setItem('jf_token', 'still-valid')
    const err = httpError(401, '用户名或密码错误', '/auth/login')

    await expect(handlers.responseError(err)).rejects.toBe(err)

    expect(elMessageError).toHaveBeenCalledWith('用户名或密码错误')
    expect(elMessageError).not.toHaveBeenCalledWith('请先登录')
    // 关键：登录失败只是这次输入错了，不该把已有的登录态清掉
    expect(localStorage.getItem('jf_token')).toBe('still-valid')
    expect(window.location.href).toBe('/')
  })

  it('注册接口 401 同样不当成会话失效', async () => {
    const err = httpError(401, '注册失败', '/auth/register')

    await expect(handlers.responseError(err)).rejects.toBe(err)

    expect(elMessageError).toHaveBeenCalledWith('注册失败')
    expect(window.location.href).toBe('/')
  })

  it('其他接口 401 视为会话失效：提示请先登录、清登录态并跳登录页', async () => {
    localStorage.setItem('jf_token', 'expired')
    localStorage.setItem('jf_user', '{"id":1}')
    const err = httpError(401, '未登录或登录已过期', '/auth/me')

    await expect(handlers.responseError(err)).rejects.toBe(err)

    expect(elMessageError).toHaveBeenCalledWith('请先登录')
    expect(localStorage.getItem('jf_token')).toBeNull()
    expect(localStorage.getItem('jf_user')).toBeNull()
    expect(window.location.href).toBe('/login')
  })

  it('已在登录页时，其他接口 401 不重复跳转（避免刷新循环）', async () => {
    window.location = { pathname: '/login', href: '/login' }
    const err = httpError(401, '未登录或登录已过期', '/auth/me')

    await expect(handlers.responseError(err)).rejects.toBe(err)

    expect(window.location.href).toBe('/login')
  })

  it('非 401 错误展示后端消息，不动登录态', async () => {
    localStorage.setItem('jf_token', 'token')
    const err = httpError(400, '板块名已存在', '/boards')

    await expect(handlers.responseError(err)).rejects.toBe(err)

    expect(elMessageError).toHaveBeenCalledWith('板块名已存在')
    expect(localStorage.getItem('jf_token')).toBe('token')
  })

  it('业务失败（HTTP 200 + success:false）走响应成功分支并弹出消息', async () => {
    const rejected = handlers.response({
      data: { success: false, message: '只能操作自己的帖子' }
    })

    await expect(rejected).rejects.toThrow('只能操作自己的帖子')
    expect(elMessageError).toHaveBeenCalledWith('只能操作自己的帖子')
  })

  it('成功响应解包出 data 字段', () => {
    expect(handlers.response({ data: { success: true, data: { id: 7 } } })).toEqual({ id: 7 })
  })
})
