import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const push = vi.fn()
const route = { params: {}, query: {}, fullPath: '/posts/create' }
vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => ({ push, back: vi.fn() })
}))

vi.mock('@/api', () => ({
  boardApi: { list: vi.fn(() => Promise.resolve([{ id: 1, name: '后端' }])) },
  postApi: { detail: vi.fn(), create: vi.fn(), update: vi.fn() }
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn() }
}))

import PostEditView from './PostEditView.vue'

// 组件用 useAuthStore()（setup 里），必须装上 pinia
let pinia

// el-input 必须真实实现 v-model，否则没法通过输入驱动预览；
// 只有 type="textarea" 才渲染 <textarea>，标题输入框仍是 <input>，
// 这样 find('textarea') 唯一命中正文，断言针对的就是正文而非标题。
const stubs = {
  'el-card': { template: '<div><slot /></div>' },
  'el-form': { template: '<form><slot /></form>', methods: { validate: () => Promise.resolve() } },
  'el-form-item': { template: '<div><slot /></div>' },
  'el-select': { template: '<div><slot /></div>' },
  'el-option': { template: '<div />' },
  'el-button': { template: '<button><slot /></button>' },
  'el-input': {
    props: ['modelValue', 'type'],
    emits: ['update:modelValue'],
    template: `
      <textarea
        v-if="type === 'textarea'"
        :value="modelValue"
        @input="$emit('update:modelValue', $event.target.value)"
      />
      <input
        v-else
        :value="modelValue"
        @input="$emit('update:modelValue', $event.target.value)"
      />
    `
  },
  'el-tabs': { template: '<div class="tabs"><slot /></div>' },
  'el-tab-pane': { template: '<div><slot /></div>' }
}

function mountEditor() {
  return mount(PostEditView, {
    global: {
      plugins: [pinia],
      stubs,
      directives: { loading: () => {} },
      provide: {}
    }
  })
}

function flush() {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

describe('PostEditView Markdown 预览', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
    window.matchMedia = vi.fn().mockImplementation((query) => ({
      matches: false,
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn()
    }))
  })

  it('桌面端左右分栏，输入正文时预览实时渲染', async () => {
    const wrapper = mountEditor()
    await flush()

    expect(wrapper.find('.editor-grid').exists()).toBe(true)
    await wrapper.find('textarea').setValue('## 预览标题')
    expect(wrapper.find('.preview').html()).toContain('<h2>预览标题</h2>')
  })

  it('窄屏显示编辑/预览 tab 而不是分栏', async () => {
    window.matchMedia = vi.fn().mockImplementation((query) => ({
      matches: true,
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn()
    }))
    const wrapper = mountEditor()
    await flush()

    expect(wrapper.find('.editor-grid').exists()).toBe(false)
    expect(wrapper.find('.tabs').exists()).toBe(true)
  })
})
