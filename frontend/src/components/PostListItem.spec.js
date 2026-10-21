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

  it('传入 keyword 时只给标题里的命中片段套 <mark>', () => {
    const post = postPayload({ id: 11, title: '全文检索与 Java', tags: [] })
    const wrapper = mount(PostListItem, { props: { post, keyword: 'java' }, global: { stubs } })

    const marks = wrapper.findAll('mark.hit')
    expect(marks).toHaveLength(1)
    expect(marks[0].text()).toBe('Java')
    expect(wrapper.find('.post-title').text()).toBe('全文检索与 Java')
    expect(wrapper.find('.post-snippet').exists()).toBe(false)
  })

  it('传入 snippet 时在标题下渲染片段并高亮命中词', () => {
    const post = postPayload({ id: 12, title: '标题不含关键词', tags: [] })
    const wrapper = mount(PostListItem, {
      props: { post, keyword: '检索', snippet: '…这里提到检索…' },
      global: { stubs }
    })

    expect(wrapper.find('.post-snippet').text()).toContain('检索')
    expect(wrapper.find('.post-snippet mark.hit').text()).toBe('检索')
    // 标题没命中：标题里不应出现 mark
    expect(wrapper.find('.post-title mark').exists()).toBe(false)
  })

  it('标题与片段里的 HTML 只作为文本渲染，不会生成元素（XSS 防护）', () => {
    const payload = '<img src=x onerror=alert(1)>'
    const post = postPayload({ id: 13, title: `标题 ${payload}`, tags: [] })
    const wrapper = mount(PostListItem, {
      props: { post, keyword: 'img', snippet: `片段 ${payload}` },
      global: { stubs }
    })

    // 文本层高亮走文本节点插值，不是 v-html：标签必须原样可见且不解析
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.find('.post-title').text()).toContain(payload)
    expect(wrapper.find('.post-snippet').text()).toContain(payload)
    expect(wrapper.find('mark.hit').exists()).toBe(true)
  })

  it('关键词不在标题与片段中时保持原文且不产生高亮', () => {
    const post = postPayload({ id: 14, title: 'Vue 入门', tags: [] })
    const wrapper = mount(PostListItem, {
      props: { post, keyword: 'java', snippet: '这篇讲组件与响应式' },
      global: { stubs }
    })

    expect(wrapper.find('mark').exists()).toBe(false)
    expect(wrapper.find('.post-title').text()).toBe('Vue 入门')
    expect(wrapper.find('.post-snippet').text()).toBe('这篇讲组件与响应式')
  })
})
