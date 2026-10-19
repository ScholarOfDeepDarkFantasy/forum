import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

import PostListItem from './PostListItem.vue'
import { usePostActions } from '@/composables/usePostActions'

const stubs = {
  'el-tag': { template: '<span><slot /></span>' },
  'el-icon': { template: '<i><slot /></i>' }
}

function postPayload(over = {}) {
  return {
    id: 7,
    title: 'T',
    tags: ['java'],
    authorUsername: 'u',
    boardName: 'b',
    viewCount: 1,
    commentCount: 2,
    likeCount: 3,
    liked: false,
    ...over
  }
}

describe('PostListItem', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
  })

  it('点击行进入详情，点击标签进入标签页且不触发行跳转', async () => {
    const post = postPayload()
    const wrapper = mount(PostListItem, { props: { post }, global: { stubs } })

    await wrapper.trigger('click')
    expect(push).toHaveBeenCalledWith('/posts/7')

    push.mockClear()
    await wrapper.find('.tags span').trigger('click')
    expect(push).toHaveBeenCalledWith('/tags/java')
    // @click.stop 必须拦住冒泡，否则点标签会同时打开帖子详情
    expect(push).not.toHaveBeenCalledWith('/posts/7')
  })

  it('标签名按 URL 规则编码后再跳转', async () => {
    const post = postPayload({ id: 9, tags: ['C#'] })
    const wrapper = mount(PostListItem, { props: { post }, global: { stubs } })

    await wrapper.find('.tags span').trigger('click')

    expect(push).toHaveBeenCalledWith('/tags/C%23')
  })

  it('点赞按钮发出 like 事件并使用共享计数', async () => {
    const post = postPayload({ id: 8, title: 'T', tags: [], viewCount: 0, commentCount: 0, likeCount: 5, liked: true })
    usePostActions().syncFromPost(post)
    const wrapper = mount(PostListItem, { props: { post }, global: { stubs } })

    expect(wrapper.find('.like-btn').text()).toContain('5')
    expect(wrapper.find('.like-btn').classes()).toContain('active')
    await wrapper.find('.like-btn').trigger('click')
    expect(wrapper.emitted('like')).toEqual([[post]])
  })

  it('渲染置顶/精华标记与浏览、评论数', () => {
    const post = postPayload({ id: 10, isPinned: true, isExquisite: true, viewCount: 12, commentCount: 4, tags: [] })
    const wrapper = mount(PostListItem, { props: { post }, global: { stubs } })

    const text = wrapper.text()
    expect(text).toContain('置顶')
    expect(text).toContain('精华')
    expect(text).toContain('12')
    expect(text).toContain('4')
  })
})
