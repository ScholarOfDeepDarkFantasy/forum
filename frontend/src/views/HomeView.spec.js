import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

// vue-router 的 useRoute/useRouter 是注入式依赖，@vue/test-utils 的 `mocks` 选项
// 只能替换 this.$route（Options API），对 <script setup> 里的 useRoute() 无效。
// 故这里直接 mock 模块本身，这是组件级用例下最轻的做法：无需装配真实 router、
// 也就无需为路由表引入额外依赖。
const push = vi.fn()
const route = { query: {}, name: 'home', fullPath: '/', path: '/' }
vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => ({ push })
}))

vi.mock('@/api', () => ({
  boardApi: { list: vi.fn() },
  postApi: { list: vi.fn(), like: vi.fn() }
}))

// 必须写全 .vue 后缀：同目录下的 HomeView.spec.js 会与 HomeView.vue 争抢
// 无扩展名的 "./HomeView" 解析，Vite 不会自动补 .vue。
import HomeView from './HomeView.vue'
import { boardApi, postApi } from '@/api'
import { usePostActions } from '@/composables/usePostActions'
import { useAuthStore } from '@/stores/auth'

/** 只渲染结构，Element Plus 组件全部打桩，避免引入 EP 的样式与副作用 */
const stubs = {
  'el-card': { template: '<div><slot /></div>' },
  'el-tag': { template: '<span><slot /></span>' },
  'el-input': { template: '<input />' },
  'el-button': { template: '<button><slot /></button>' },
  'el-pagination': { template: '<div />' },
  'el-empty': { template: '<div />' },
  'el-icon': { template: '<i><slot /></i>' }
}

let pinia

function mountHome() {
  // 复用同一个 pinia 实例：mount 若再 createPinia()，组件取到的 store 与本用例
  // 配置的 store 就不是同一个对象，token 设置会悄无声息地失效。
  // directives 打桩是为了消掉模板里 v-loading 的解析告警（EP 的指令需装插件才注册）。
  return mount(HomeView, {
    global: { plugins: [pinia], stubs, directives: { loading: () => {} } }
  })
}

/**
 * 每个用例使用独立 postId：usePostActions 的 stateMap 与 locallyToggled 都是
 * 模块级共享状态，跨用例复用同一 id 会让断言依赖执行顺序。
 */
describe('HomeView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
    boardApi.list.mockResolvedValue([{ id: 1, name: '后端', postCount: 3 }])
    postApi.list.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0 })
  })

  it('挂载时加载版块并渲染工具栏', async () => {
    const wrapper = mountHome()
    await flush()

    expect(boardApi.list).toHaveBeenCalled()
    expect(wrapper.find('.toolbar').exists()).toBe(true)
    expect(wrapper.text()).toContain('后端')
  })

  it('挂载时用默认查询参数加载帖子', async () => {
    mountHome()
    await flush()

    expect(postApi.list).toHaveBeenCalledWith(
      expect.objectContaining({ boardId: null, keyword: '', page: 0 })
    )
  })

  it('把列表响应里的 liked 同步进共享状态', async () => {
    postApi.list.mockResolvedValue({
      content: [{ id: 101, title: '帖子', likeCount: 7, liked: true }],
      totalElements: 1,
      totalPages: 1
    })
    const wrapper = mountHome()
    await flush()

    // 共享状态被写入，点赞按钮呈现「已点赞」样式与真实计数
    expect(usePostActions().stateFor(101)).toMatchObject({ liked: true, likeCount: 7 })
    expect(wrapper.find('.like-btn').exists()).toBe(true)
    expect(wrapper.find('.like-btn').text()).toContain('7')
  })

  it('已点赞的帖子渲染 active 样式，未点赞的不渲染', async () => {
    postApi.list.mockResolvedValue({
      content: [
        { id: 102, title: '已赞', likeCount: 3, liked: true },
        { id: 103, title: '未赞', likeCount: 4, liked: false }
      ],
      totalElements: 2,
      totalPages: 1
    })
    const wrapper = mountHome()
    await flush()

    const btns = wrapper.findAll('.like-btn')
    expect(btns).toHaveLength(2)
    expect(btns[0].classes()).toContain('active')
    expect(btns[1].classes()).not.toContain('active')
  })

  it('点击点赞按钮切换共享状态并刷新计数', async () => {
    useAuthStore().token = 'fake-token'
    postApi.list.mockResolvedValue({
      content: [{ id: 104, title: '帖子', likeCount: 3, liked: false }],
      totalElements: 1,
      totalPages: 1
    })
    postApi.like.mockResolvedValue({ active: true, count: 4 })
    const wrapper = mountHome()
    await flush()

    await wrapper.find('.like-btn').trigger('click')
    await flush()

    expect(postApi.like).toHaveBeenCalledWith(104)
    expect(usePostActions().stateFor(104)).toMatchObject({ liked: true, likeCount: 4 })
    expect(wrapper.find('.like-btn').classes()).toContain('active')
    expect(wrapper.find('.like-btn').text()).toContain('4')
  })

  it('点击点赞按钮不应跳转到帖子详情', async () => {
    useAuthStore().token = 'fake-token'
    postApi.list.mockResolvedValue({
      content: [{ id: 105, title: '帖子', likeCount: 1, liked: false }],
      totalElements: 1,
      totalPages: 1
    })
    postApi.like.mockResolvedValue({ active: true, count: 2 })
    const wrapper = mountHome()
    await flush()

    // 行本身可点击跳转，按钮必须用 @click.stop 阻断冒泡
    await wrapper.find('.like-btn').trigger('click')
    await flush()

    expect(push).not.toHaveBeenCalledWith('/posts/105')
  })

  it('游客点击点赞跳登录页并带上 redirect，且不发起点赞请求', async () => {
    postApi.list.mockResolvedValue({
      content: [{ id: 106, title: '帖子', likeCount: 1, liked: false }],
      totalElements: 1,
      totalPages: 1
    })
    const wrapper = mountHome()
    await flush()

    await wrapper.find('.like-btn').trigger('click')
    await flush()

    expect(postApi.like).not.toHaveBeenCalled()
    expect(push).toHaveBeenCalledWith({ name: 'login', query: { redirect: '/' } })
  })

  it('点击行跳转到帖子详情', async () => {
    postApi.list.mockResolvedValue({
      content: [{ id: 107, title: '帖子', likeCount: 1, liked: false }],
      totalElements: 1,
      totalPages: 1
    })
    const wrapper = mountHome()
    await flush()

    await wrapper.find('.post-item').trigger('click')

    expect(push).toHaveBeenCalledWith('/posts/107')
  })
})

/** 清空微任务队列，等待 onMounted / 事件处理器里的 await 链结束 */
function flush() {
  return new Promise((resolve) => setTimeout(resolve, 0))
}
