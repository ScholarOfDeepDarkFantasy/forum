import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

// vi.mock 的工厂会被提升到 import 之前，跨行引用必须用 vi.hoisted
const { streamMock, useNotificationStreamMock } = vi.hoisted(() => {
  const stream = { start: vi.fn(), stop: vi.fn(), state: { value: 'closed' } }
  return { streamMock: stream, useNotificationStreamMock: vi.fn() }
})

vi.mock('@/composables/useNotificationStream', async () => {
  const { onUnmounted } = await import('vue')
  return {
    // App.vue 只调用一次并忽略返回值；真实 composable 在组件卸载时 stop，
    // mock 也必须注册同样的清理，否则「卸载停止」这条契约永远测不到。
    useNotificationStream: () => {
      useNotificationStreamMock()
      onUnmounted(() => streamMock.stop())
      return streamMock
    }
  }
})

// App.vue 的 setup 调用 useRouter()；不装真实路由插件时注入不到，mock 掉最省事。
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

import App from '@/App.vue'

/** App 依赖 Element Plus 全局组件与路由；这里用最小 stub，只验证接线 */
function mountApp() {
  return mount(App, {
    global: {
      // 未安装真实 router 插件；模板里 `<router-view :key="$route.fullPath">` 仍会求值，
      // 缺省时 $route 为 undefined 会让整个挂载抛 TypeError。
      mocks: { $route: { fullPath: '/' } },
      stubs: {
        'router-link': { props: ['to'], template: '<a><slot /></a>' },
        'router-view': true,
        'el-container': { template: '<div><slot /></div>' },
        'el-header': { template: '<header><slot /></header>' },
        'el-main': { template: '<main><slot /></main>' },
        'el-footer': { template: '<footer><slot /></footer>' },
        'el-icon': { template: '<i><slot /></i>' },
        'el-badge': { template: '<span class="badge-stub"><slot /></span>' },
        'el-button': { template: '<button><slot /></button>' },
        'el-dropdown': { template: '<div><slot /></div>' },
        'el-dropdown-menu': { template: '<div><slot /></div>' },
        'el-dropdown-item': { template: '<div><slot /></div>' },
        ThemeToggle: true,
        HeaderSearch: true,
        UserAvatar: true
      }
    }
  })
}

describe('App.vue 实时通知接线', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('挂载时启用实时通知 composable', () => {
    mountApp()
    expect(useNotificationStreamMock).toHaveBeenCalledTimes(1)
  })

  it('未登录不显示通知角标，卸载不抛错', () => {
    const wrapper = mountApp()
    expect(wrapper.find('.bell-badge').exists()).toBe(false)
    expect(() => wrapper.unmount()).not.toThrow()
  })

  it('组件卸载时调用 stop() 断开实时通知', () => {
    const wrapper = mountApp()
    expect(streamMock.stop).not.toHaveBeenCalled()

    wrapper.unmount()

    expect(streamMock.stop).toHaveBeenCalledTimes(1)
  })
})
