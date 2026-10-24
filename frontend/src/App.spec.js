import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'

// vi.mock 的工厂会被提升到 import 之前，跨行引用必须用 vi.hoisted
const { streamMock, useNotificationStreamMock, routerPush, messageSuccess } = vi.hoisted(() => {
  const stream = { start: vi.fn(), stop: vi.fn(), state: { value: 'closed' } }
  return {
    streamMock: stream,
    useNotificationStreamMock: vi.fn(),
    routerPush: vi.fn(),
    messageSuccess: vi.fn()
  }
})

vi.mock('@/composables/useNotificationStream', () => ({
  // App.vue 只调用一次并忽略返回值。组件卸载时的真实断开由 composable 自身的
  // onUnmounted 负责，其契约在 useNotificationStream.spec.js 中覆盖；这里不再
  // 由 mock 自行注册清理，避免出现「测试自己验证自己」的假覆盖。
  useNotificationStream: () => {
    useNotificationStreamMock()
    return streamMock
  }
}))

// App.vue 的 setup 调用 useRouter()；不装真实路由插件时注入不到，mock 掉最省事。
vi.mock('vue-router', () => ({ useRouter: () => ({ push: routerPush }) }))

// 只替换 ElMessage：ThemeToggle 等模块从 element-plus 具名导入，
// 整体 mock 会让这些具名导出变成 undefined。
vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, ElMessage: { success: messageSuccess, error: vi.fn() } }
})

import App from '@/App.vue'
import { useAuthStore } from '@/stores/auth'

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
        'el-dropdown': {
          // 声明 emits 后 $emit('command') 才会调用父组件的 onCommand；
          // 不声明会按原生事件透传，父监听永远不触发。
          emits: ['command'],
          template: '<div class="el-dropdown-stub"><slot /></div>'
        },
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

})

describe('App.vue 退出登录', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('后端未返回前不提示，返回后才提示成功并跳首页', async () => {
    const auth = useAuthStore()
    auth.token = 'token-1'
    auth.user = { id: 1, username: 'alice' }
    let resolveLogout
    const pending = new Promise((resolve) => {
      resolveLogout = resolve
    })
    vi.spyOn(auth, 'logout').mockImplementation(() => pending)

    const wrapper = mountApp()
    // 用 CSS 选择器取到 stub 的组件实例（VTU 的 findComponent 支持类选择器），再手动 emit
    wrapper.findComponent('.el-dropdown-stub').vm.$emit('command', 'logout')
    await nextTick()

    // 关键断言：后端还没返回，不能先宣布「已退出登录」，也不能先跳首页
    expect(auth.logout).toHaveBeenCalledTimes(1)
    expect(messageSuccess).not.toHaveBeenCalled()
    expect(routerPush).not.toHaveBeenCalled()

    resolveLogout()
    await flushPromises()

    expect(messageSuccess).toHaveBeenCalledWith('已退出登录')
    expect(routerPush).toHaveBeenCalledWith('/')
  })
})
