import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { reactive, nextTick } from 'vue'

const push = vi.fn()
const route = reactive({ name: 'home', query: {}, fullPath: '/', path: '/' })
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ push }) }))

import HeaderSearch from './HeaderSearch.vue'

/**
 * 可手动驱动的 matchMedia 桩。
 *
 * jsdom 不实现 window.matchMedia，useResponsive 会走「缺失」分支返回 false ——
 * 窄屏分支永远测不到。这里返回受控的 mql，并记录 add/remove 的调用，便于断言。
 */
function installMatchMedia(matches) {
  const mql = {
    matches,
    media: '(max-width: 768px)',
    addEventListener: vi.fn(),
    removeEventListener: vi.fn()
  }
  window.matchMedia = vi.fn(() => mql)
  return mql
}

const stubs = {
  'el-input': {
    props: ['modelValue', 'placeholder', 'maxlength'],
    emits: ['update:modelValue', 'clear'],
    // 模拟 ElInput 的 clear：先清空 v-model，再 emit('clear')
    template:
      '<div class="stub-input"><input class="search-input" :value="modelValue" :placeholder="placeholder" :maxlength="maxlength" @input="$emit(\'update:modelValue\', $event.target.value)" /><button class="stub-clear" @click="$emit(\'update:modelValue\', \'\'); $emit(\'clear\')" /><slot name="append" /></div>'
  },
  'el-button': { template: '<button class="stub-btn"><slot /></button>' },
  'el-icon': { template: '<i><slot /></i>' }
}

const wrappers = []

function mountSearch() {
  const wrapper = mount(HeaderSearch, { global: { stubs } })
  wrappers.push(wrapper)
  return wrapper
}

describe('HeaderSearch', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    route.name = 'home'
    route.query = {}
    route.fullPath = '/'
    installMatchMedia(false)
  })

  afterEach(() => {
    wrappers.forEach((w) => w.unmount())
    wrappers.length = 0
  })

  it('桌面渲染搜索输入框，回车跳到搜索页', async () => {
    const wrapper = mountSearch()

    const input = wrapper.find('input.search-input')
    expect(input.attributes('placeholder')).toBe('搜索帖子')
    expect(input.attributes('maxlength')).toBe('50')

    await input.setValue('java')
    await input.trigger('keyup.enter')

    expect(push).toHaveBeenCalledWith({ name: 'search', query: { q: 'java' } })
  })

  it('空白关键词不跳转', async () => {
    const wrapper = mountSearch()
    const input = wrapper.find('input.search-input')

    await input.setValue('   ')
    await input.trigger('keyup.enter')

    expect(push).not.toHaveBeenCalled()
  })

  it('已在搜索页且关键词相同时不重复跳转', async () => {
    route.name = 'search'
    route.query = { q: 'java' }
    route.fullPath = '/search?q=java'
    const wrapper = mountSearch()

    const input = wrapper.find('input.search-input')
    expect(input.element.value).toBe('java')
    await input.trigger('keyup.enter')

    expect(push).not.toHaveBeenCalled()
  })

  it('窄屏只渲染图标按钮，点击进入搜索页', async () => {
    installMatchMedia(true)
    const wrapper = mountSearch()

    expect(wrapper.find('input.search-input').exists()).toBe(false)
    const button = wrapper.find('.stub-btn')
    expect(button.exists()).toBe(true)
    // 只有图标的按钮必须有无障碍名称
    expect(button.attributes('aria-label')).toBe('搜索')

    await button.trigger('click')

    expect(push).toHaveBeenCalledWith({ name: 'search' })
  })

  it('地址栏 ?q= 变化时同步输入框', async () => {
    route.name = 'search'
    route.query = { q: 'spring' }
    route.fullPath = '/search?q=spring'
    const wrapper = mountSearch()

    expect(wrapper.find('input.search-input').element.value).toBe('spring')

    route.query = { q: 'vue' }
    route.fullPath = '/search?q=vue'
    await nextTick()

    expect(wrapper.find('input.search-input').element.value).toBe('vue')
  })

  // —— 以下用例覆盖 Task 3 质量评审的 clear 交互与码点长度 ——

  it('在搜索页清空输入框会回到不带关键词的搜索页', async () => {
    route.name = 'search'
    route.query = { q: 'java' }
    route.fullPath = '/search?q=java'
    const wrapper = mountSearch()

    await wrapper.find('.stub-clear').trigger('click')

    expect(push).toHaveBeenCalledWith({ name: 'search' })
  })

  it('不在搜索页时清空输入框只清文字，不跳转', async () => {
    const wrapper = mountSearch()

    await wrapper.find('input.search-input').setValue('java')
    await wrapper.find('.stub-clear').trigger('click')

    expect(push).not.toHaveBeenCalled()
    expect(wrapper.find('input.search-input').element.value).toBe('')
  })

  it('超过 50 个 Unicode 码点不跳转', async () => {
    const wrapper = mountSearch()
    const input = wrapper.find('input.search-input')

    await input.setValue('a'.repeat(50) + 'b')
    await input.trigger('keyup.enter')

    expect(push).not.toHaveBeenCalled()
  })

  it('50 个 emoji（100 个 UTF-16 码元）按码点计数仍可提交', async () => {
    const wrapper = mountSearch()
    const input = wrapper.find('input.search-input')

    const q = '😀'.repeat(50)
    await input.setValue(q)
    await input.trigger('keyup.enter')

    expect(push).toHaveBeenCalledWith({ name: 'search', query: { q } })
  })
})
