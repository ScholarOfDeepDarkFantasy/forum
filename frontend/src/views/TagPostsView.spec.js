import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const push = vi.fn()
const route = { params: { name: 'java' }, query: {}, fullPath: '/tags/java', name: 'tag-posts' }
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ push }) }))
vi.mock('@/api', () => ({
  tagApi: { list: vi.fn(), posts: vi.fn() }
}))
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn() } }))

import TagPostsView from './TagPostsView.vue'
import { tagApi } from '@/api'

const stubs = {
  'el-card': { template: '<div><slot name="header" /><slot /></div>' },
  'el-tag': { template: '<span><slot /></span>' },
  'el-pagination': {
    props: ['total', 'pageSize', 'currentPage', 'layout'],
    emits: ['current-change'],
    template: '<button class="pager-next" @click="$emit(\'current-change\', currentPage + 1)" />'
  },
  'el-empty': { props: ['description'], template: '<div class="empty">{{ description }}</div>' },
  'el-icon': { template: '<i><slot /></i>' },
  'el-button': { template: '<button><slot /></button>' },
  'router-link': { template: '<a><slot /></a>' }
}

const directives = { loading: () => {} }

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

let pinia

function mountTagPosts() {
  return mount(TagPostsView, {
    global: { plugins: [pinia], stubs, directives }
  })
}

function postPayload(over = {}) {
  return {
    id: 11,
    title: '帖子A',
    tags: ['java'],
    authorUsername: 'u',
    boardName: 'b',
    viewCount: 0,
    commentCount: 0,
    likeCount: 0,
    liked: false,
    ...over
  }
}

describe('TagPostsView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
  })

  it('按路由标签名加载帖子并显示空状态', async () => {
    tagApi.posts.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0 })
    const wrapper = mountTagPosts()
    await flush()

    expect(tagApi.posts).toHaveBeenCalledWith('java', expect.objectContaining({ page: 0 }))
    expect(wrapper.text()).toContain('java')
    expect(wrapper.find('.empty').exists()).toBe(true)
    expect(wrapper.text()).toContain('该标签下暂无帖子')
  })

  it('渲染帖子行、总数，游客点赞跳登录并带 redirect', async () => {
    tagApi.posts.mockResolvedValue({
      content: [postPayload()],
      totalElements: 1,
      totalPages: 1
    })
    const wrapper = mountTagPosts()
    await flush()

    expect(wrapper.findAll('.post-item')).toHaveLength(1)
    expect(wrapper.text()).toContain('帖子A')
    expect(wrapper.text()).toContain('1 篇')

    await wrapper.find('.like-btn').trigger('click')
    await flush()

    expect(push).toHaveBeenCalledWith({ name: 'login', query: { redirect: '/tags/java' } })
    expect(push).not.toHaveBeenCalledWith('/posts/11')
  })

  it('分页切换请求下一页', async () => {
    tagApi.posts.mockResolvedValue({
      content: [],
      totalElements: 21,
      totalPages: 3
    })
    const wrapper = mountTagPosts()
    await flush()

    await wrapper.find('.pager-next').trigger('click')
    await flush()

    expect(tagApi.posts).toHaveBeenLastCalledWith(
      'java',
      expect.objectContaining({ page: 1, size: 10 })
    )
  })

  it('加载失败展示错误态，重试成功后渲染空状态', async () => {
    tagApi.posts
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce({ content: [], totalElements: 0, totalPages: 0 })
    const wrapper = mountTagPosts()
    await flush()

    expect(wrapper.find('.error-state').exists()).toBe(true)
    expect(wrapper.text()).toContain('标签帖子加载失败')

    await wrapper.find('.retry-btn').trigger('click')
    await flush()

    expect(tagApi.posts).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.error-state').exists()).toBe(false)
    expect(wrapper.text()).toContain('该标签下暂无帖子')
  })
})
