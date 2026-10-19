import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'

const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))
vi.mock('@/api', () => ({
  tagApi: { list: vi.fn(), posts: vi.fn() }
}))
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn() } }))

import TagsView from './TagsView.vue'
import { tagApi } from '@/api'

const stubs = {
  // header 槽必须渲染：关键词输入框在卡片头里
  'el-card': { template: '<div><slot name="header" /><slot /></div>' },
  'el-input': {
    props: ['modelValue'],
    emits: ['update:modelValue', 'keyup', 'clear'],
    template:
      '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" @keyup="$emit(\'keyup\', $event)" />'
  },
  'el-button': { template: '<button><slot /></button>' },
  'el-tag': { template: '<span class="tag"><slot /></span>' },
  'el-pagination': {
    props: ['total', 'pageSize', 'currentPage', 'layout'],
    emits: ['current-change'],
    template: '<button class="pager-next" @click="$emit(\'current-change\', currentPage + 1)" />'
  },
  'el-empty': { props: ['description'], template: '<div class="empty">{{ description }}</div>' }
}

// v-loading 由 ElementPlus 插件注册，组件级用例里用空指令消掉解析告警
const directives = { loading: () => {} }

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

function mountTags() {
  return mount(TagsView, { global: { stubs, directives } })
}

describe('TagsView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    tagApi.list.mockResolvedValue({
      content: [
        { id: 1, name: 'java', postCount: 9 },
        { id: 2, name: 'spring', postCount: 3 }
      ],
      totalElements: 2,
      totalPages: 1
    })
  })

  it('渲染标签与帖子数，点击跳转标签帖子页', async () => {
    const wrapper = mountTags()
    await flush()

    expect(tagApi.list).toHaveBeenCalled()
    expect(wrapper.text()).toContain('java')
    expect(wrapper.text()).toContain('9')
    await wrapper.find('.tag').trigger('click')
    expect(push).toHaveBeenCalledWith('/tags/java')
  })

  it('关键词检索重置到第一页并按 keyword 请求', async () => {
    tagApi.list.mockResolvedValue({
      content: [{ id: 1, name: 'spring', postCount: 3 }],
      totalElements: 31,
      totalPages: 2
    })
    const wrapper = mountTags()
    await flush()

    // 先翻到第二页，确认搜索会重置 page
    await wrapper.find('.pager-next').trigger('click')
    await flush()
    expect(tagApi.list).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }))

    const input = wrapper.find('input')
    await input.setValue('spring')
    await input.trigger('keyup', { key: 'Enter' })
    await flush()

    expect(tagApi.list).toHaveBeenLastCalledWith(
      expect.objectContaining({ keyword: 'spring', page: 0, size: 30 })
    )
  })

  it('分页切换请求下一页', async () => {
    tagApi.list.mockResolvedValue({
      content: [{ id: 1, name: 'java', postCount: 9 }],
      totalElements: 31,
      totalPages: 2
    })
    const wrapper = mountTags()
    await flush()

    await wrapper.find('.pager-next').trigger('click')
    await flush()

    expect(tagApi.list).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 1, size: 30 })
    )
  })

  it('无标签时展示空状态', async () => {
    tagApi.list.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0 })
    const wrapper = mountTags()
    await flush()

    expect(wrapper.find('.empty').exists()).toBe(true)
    expect(wrapper.text()).toContain('暂无标签')
  })

  it('加载失败展示错误态，重试成功后渲染列表', async () => {
    tagApi.list
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce({
        content: [{ id: 1, name: 'java', postCount: 9 }],
        totalElements: 1,
        totalPages: 1
      })
    const wrapper = mountTags()
    await flush()

    expect(wrapper.find('.error-state').exists()).toBe(true)
    expect(wrapper.text()).toContain('标签加载失败')

    await wrapper.find('.retry-btn').trigger('click')
    await flush()

    expect(tagApi.list).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.error-state').exists()).toBe(false)
    expect(wrapper.text()).toContain('java')
  })
})
