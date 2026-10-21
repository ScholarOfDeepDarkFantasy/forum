import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { reactive } from 'vue'
import { createPinia, setActivePinia } from 'pinia'

const replace = vi.fn()
const push = vi.fn()
// 真实路由对象是响应式的：computed(keyword) 与 watch 都依赖它，故用 reactive 模拟
const route = reactive({ name: 'search', query: {}, fullPath: '/search', path: '/search' })
vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => ({ replace, push })
}))
vi.mock('@/api', () => ({ postApi: { search: vi.fn(), like: vi.fn() } }))
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn() } }))

import SearchView from './SearchView.vue'
import { postApi } from '@/api'

const stubs = {
  'el-card': { template: '<div><slot name="header" /><slot /></div>' },
  'el-input': {
    props: ['modelValue', 'placeholder', 'maxlength'],
    emits: ['update:modelValue', 'clear'],
    // 模拟 ElInput 的 clear：先清空 v-model，再 emit('clear')
    template:
      '<div class="stub-input"><input class="search-input" :value="modelValue" :placeholder="placeholder" :maxlength="maxlength" @input="$emit(\'update:modelValue\', $event.target.value)" /><button class="stub-clear" @click="$emit(\'update:modelValue\', \'\'); $emit(\'clear\')" /><slot name="append" /></div>'
  },
  'el-button': { template: '<button class="stub-btn"><slot /></button>' },
  'el-empty': { props: ['description'], template: '<div class="empty">{{ description }}</div>' },
  'el-pagination': {
    props: ['total', 'pageSize', 'currentPage', 'layout'],
    emits: ['current-change'],
    template: '<button class="pager-next" @click="$emit(\'current-change\', currentPage + 1)" />'
  },
  // 把 keyword/snippet 透到 DOM，才能断言「搜索页真的把高亮参数传给了帖子行」
  PostListItem: {
    props: ['post', 'keyword', 'snippet'],
    template:
      '<div class="post-item" :data-keyword="keyword" :data-snippet="snippet">{{ post.title }}</div>'
  }
}

// 把 v-loading 的绑定值写到 DOM 上，才能断言「清空关键词后加载态被复位」
const directives = {
  loading: {
    mounted(el, binding) {
      el.dataset.loading = String(binding.value)
    },
    updated(el, binding) {
      el.dataset.loading = String(binding.value)
    }
  }
}
const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

let pinia
const wrappers = []

function mountSearch() {
  const wrapper = mount(SearchView, {
    global: { plugins: [pinia], stubs, directives }
  })
  wrappers.push(wrapper)
  return wrapper
}

function postPayload(over = {}) {
  return {
    id: 11,
    title: '帖子A',
    tags: [],
    authorUsername: 'u',
    boardName: 'b',
    viewCount: 0,
    commentCount: 0,
    likeCount: 0,
    liked: false,
    snippet: '…正文里提到全文检索…',
    ...over
  }
}

describe('SearchView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    route.name = 'search'
    route.query = {}
    route.fullPath = '/search'
    pinia = createPinia()
    setActivePinia(pinia)
  })

  // 已挂载的组件会一直 watch 共享的 route，测试之间必须卸载，避免串扰
  afterEach(() => {
    wrappers.forEach((w) => w.unmount())
    wrappers.length = 0
  })

  it('未带 q 时显示「输入关键词开始搜索」且不发请求', async () => {
    const wrapper = mountSearch()
    await flush()

    expect(postApi.search).not.toHaveBeenCalled()
    expect(wrapper.find('.empty').text()).toBe('输入关键词开始搜索')
  })

  it('按 ?q= 加载结果并渲染计数、把 keyword/snippet 传给帖子行', async () => {
    route.query = { q: '全文' }
    route.fullPath = '/search?q=全文'
    postApi.search.mockResolvedValue({ content: [postPayload()], totalElements: 12, totalPages: 2 })

    const wrapper = mountSearch()
    await flush()

    expect(postApi.search).toHaveBeenCalledWith({ keyword: '全文', page: 0, size: 10 })
    expect(wrapper.text()).toContain('共 12 条结果')
    const rows = wrapper.findAll('.post-item')
    expect(rows).toHaveLength(1)
    expect(rows[0].attributes('data-keyword')).toBe('全文')
    expect(rows[0].attributes('data-snippet')).toContain('全文')
  })

  it('无结果时显示带关键词的空态文案', async () => {
    route.query = { q: '没命中' }
    route.fullPath = '/search?q=没命中'
    postApi.search.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0 })

    const wrapper = mountSearch()
    await flush()

    expect(wrapper.find('.empty').text()).toBe('没有找到与 “没命中” 相关的帖子')
  })

  it('加载失败显示错误态，重试成功后恢复', async () => {
    route.query = { q: '全文' }
    route.fullPath = '/search?q=全文'
    postApi.search
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce({ content: [], totalElements: 0, totalPages: 0 })

    const wrapper = mountSearch()
    await flush()

    expect(wrapper.find('.error-state').text()).toContain('搜索失败，请稍后重试')

    await wrapper.find('.retry-btn').trigger('click')
    await flush()

    expect(postApi.search).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.error-state').exists()).toBe(false)
    expect(wrapper.find('.empty').text()).toBe('没有找到与 “全文” 相关的帖子')
  })

  it('分页切换请求下一页', async () => {
    route.query = { q: '全文' }
    route.fullPath = '/search?q=全文'
    postApi.search.mockResolvedValue({ content: [postPayload()], totalElements: 21, totalPages: 3 })

    const wrapper = mountSearch()
    await flush()

    await wrapper.find('.pager-next').trigger('click')
    await flush()

    expect(postApi.search).toHaveBeenLastCalledWith({ keyword: '全文', page: 1, size: 10 })
  })

  it('提交新关键词走 router.replace，并在 URL 变化后重新加载', async () => {
    route.query = { q: '旧词' }
    route.fullPath = '/search?q=旧词'
    postApi.search.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0 })

    const wrapper = mountSearch()
    await flush()

    const input = wrapper.find('input.search-input')
    await input.setValue('spring')
    await input.trigger('keyup.enter')

    expect(replace).toHaveBeenCalledWith({ name: 'search', query: { q: 'spring' } })

    route.query = { q: 'spring' }
    route.fullPath = '/search?q=spring'
    await flush()

    expect(postApi.search).toHaveBeenLastCalledWith({ keyword: 'spring', page: 0, size: 10 })
  })

  it('过期响应被序号丢弃，不覆盖新关键词的结果', async () => {
    let resolveOld
    postApi.search
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolveOld = resolve
          })
      )
      .mockResolvedValueOnce({ content: [postPayload({ id: 2, title: '新帖' })], totalElements: 1, totalPages: 1 })
    route.query = { q: '旧词' }
    route.fullPath = '/search?q=旧词'

    const wrapper = mountSearch()
    await flush()

    route.query = { q: '新词' }
    route.fullPath = '/search?q=新词'
    await flush()
    expect(wrapper.text()).toContain('新帖')

    resolveOld({ content: [postPayload({ id: 1, title: '旧帖' })], totalElements: 1, totalPages: 1 })
    await flush()

    expect(wrapper.text()).toContain('新帖')
    expect(wrapper.text()).not.toContain('旧帖')
  })

  it('换关键词时页码重置为 0', async () => {
    route.query = { q: '旧词' }
    route.fullPath = '/search?q=旧词'
    postApi.search.mockResolvedValue({ content: [postPayload()], totalElements: 21, totalPages: 3 })

    const wrapper = mountSearch()
    await flush()
    await wrapper.find('.pager-next').trigger('click')
    await flush()
    expect(postApi.search).toHaveBeenLastCalledWith({ keyword: '旧词', page: 1, size: 10 })

    route.query = { q: '新词' }
    route.fullPath = '/search?q=新词'
    await flush()

    expect(postApi.search).toHaveBeenLastCalledWith({ keyword: '新词', page: 0, size: 10 })
  })

  // —— 以下用例覆盖 Task 3 质量评审状态清理与输入框草稿相关的修复 ——

  it('清空关键词会清掉失败残留的错误态，新关键词可重新搜索', async () => {
    route.query = { q: '坏词' }
    route.fullPath = '/search?q=坏词'
    postApi.search
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce({ content: [], totalElements: 0, totalPages: 0 })

    const wrapper = mountSearch()
    await flush()
    expect(wrapper.find('.error-state').exists()).toBe(true)

    // 清空地址栏关键词：错误提示不能留在页面上，否则「重试」按钮永远指向失败请求
    route.query = {}
    route.fullPath = '/search'
    await flush()

    expect(wrapper.find('.error-state').exists()).toBe(false)
    expect(wrapper.find('.empty').text()).toBe('输入关键词开始搜索')
    expect(wrapper.text()).not.toContain('条结果')

    // 重新搜索（同样的 load 路径）必须能发出新请求并恢复正常态
    route.query = { q: '新词' }
    route.fullPath = '/search?q=新词'
    await flush()

    expect(postApi.search).toHaveBeenLastCalledWith({ keyword: '新词', page: 0, size: 10 })
    expect(wrapper.find('.error-state').exists()).toBe(false)
  })

  it('清空关键词会作废在途请求并复位加载态', async () => {
    let resolveOld
    postApi.search.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveOld = resolve
        })
    )
    route.query = { q: '慢词' }
    route.fullPath = '/search?q=慢词'

    const wrapper = mountSearch()
    await flush()
    expect(wrapper.find('[data-loading]').attributes('data-loading')).toBe('true')

    route.query = {}
    route.fullPath = '/search'
    await flush()
    // 在途请求不能把空页面的 loading 一直挂着（v-loading 遮罩会挡住空态）
    expect(wrapper.find('[data-loading]').attributes('data-loading')).toBe('false')

    resolveOld({ content: [postPayload({ id: 1, title: '过期结果' })], totalElements: 1, totalPages: 1 })
    await flush()

    expect(wrapper.find('.empty').text()).toBe('输入关键词开始搜索')
    expect(wrapper.text()).not.toContain('过期结果')
  })

  it('分页不覆盖输入框里尚未提交的草稿，提交后按 URL 同步', async () => {
    route.query = { q: '全文' }
    route.fullPath = '/search?q=全文'
    postApi.search.mockResolvedValue({ content: [postPayload()], totalElements: 21, totalPages: 3 })

    const wrapper = mountSearch()
    await flush()

    const input = wrapper.find('input.search-input')
    await input.setValue('  spring  ')
    await wrapper.find('.pager-next').trigger('click')
    await flush()

    expect(postApi.search).toHaveBeenLastCalledWith({ keyword: '全文', page: 1, size: 10 })
    expect(input.element.value).toBe('  spring  ')

    // 回车才把草稿变成关键词，随后输入框按 URL 里的规范值（trim 后）同步
    await input.trigger('keyup.enter')
    expect(replace).toHaveBeenCalledWith({ name: 'search', query: { q: 'spring' } })

    route.query = { q: 'spring' }
    route.fullPath = '/search?q=spring'
    await flush()
    expect(input.element.value).toBe('spring')
  })

  it('重试不覆盖输入框里尚未提交的草稿', async () => {
    route.query = { q: '全文' }
    route.fullPath = '/search?q=全文'
    postApi.search
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce({ content: [], totalElements: 0, totalPages: 0 })

    const wrapper = mountSearch()
    await flush()
    expect(wrapper.find('.error-state').exists()).toBe(true)

    const input = wrapper.find('input.search-input')
    await input.setValue('draft')
    await wrapper.find('.retry-btn').trigger('click')
    await flush()

    expect(input.element.value).toBe('draft')
    expect(wrapper.find('.error-state').exists()).toBe(false)
  })

  it('加载中不渲染「共 N 条结果」', async () => {
    let resolveSearch
    postApi.search.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveSearch = resolve
        })
    )
    route.query = { q: '全文' }
    route.fullPath = '/search?q=全文'

    const wrapper = mountSearch()
    await flush()

    // 此时 totalElements 还是初始 0，渲染出来会误导成「共 0 条结果」
    expect(wrapper.text()).not.toContain('条结果')

    resolveSearch({ content: [postPayload()], totalElements: 12, totalPages: 2 })
    await flush()

    expect(wrapper.text()).toContain('共 12 条结果')
  })

  it('总页数不超过 1 时不渲染分页器', async () => {
    route.query = { q: '全文' }
    route.fullPath = '/search?q=全文'
    postApi.search.mockResolvedValue({ content: [postPayload()], totalElements: 1, totalPages: 1 })

    const wrapper = mountSearch()
    await flush()

    expect(wrapper.find('.pager-next').exists()).toBe(false)
  })

  it('q 只有空白时视为未搜索', async () => {
    route.query = { q: '   ' }
    route.fullPath = '/search?q=%20%20%20'

    const wrapper = mountSearch()
    await flush()

    expect(postApi.search).not.toHaveBeenCalled()
    expect(wrapper.find('.empty').text()).toBe('输入关键词开始搜索')
  })

  it('同一关键词再次回车不重复请求', async () => {
    route.query = { q: '全文' }
    route.fullPath = '/search?q=全文'
    postApi.search.mockResolvedValue({ content: [postPayload()], totalElements: 1, totalPages: 1 })

    const wrapper = mountSearch()
    await flush()

    const input = wrapper.find('input.search-input')
    await input.setValue('全文')
    await input.trigger('keyup.enter')
    await flush()

    expect(postApi.search).toHaveBeenCalledTimes(1)
    // replace mock 不会改 route.query，若 submit 继续走下去这里仍会记录一次 replace，
    // 所以必须显式断言没跳转，否则用例在「同词重复提交」回归时仍会绿
    expect(replace).not.toHaveBeenCalled()
  })

  it('点击输入框清空按钮回到空搜索页并去掉地址栏关键词', async () => {
    route.query = { q: '全文' }
    route.fullPath = '/search?q=全文'
    postApi.search.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0 })

    const wrapper = mountSearch()
    await flush()

    await wrapper.find('.stub-clear').trigger('click')
    await flush()

    expect(replace).toHaveBeenCalledWith({ name: 'search' })
  })

  it('关键词按 Unicode 码点限长 50：51 个 ASCII 不提交，50 个 emoji 可提交', async () => {
    route.query = { q: '全文' }
    route.fullPath = '/search?q=全文'
    postApi.search.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0 })

    const wrapper = mountSearch()
    await flush()

    const input = wrapper.find('input.search-input')
    await input.setValue('a'.repeat(51))
    await input.trigger('keyup.enter')
    expect(replace).not.toHaveBeenCalled()

    // 50 个 emoji 是 100 个 UTF-16 码元、但只有 50 个 code point，服务端口径允许
    const q = '😀'.repeat(50)
    await input.setValue(q)
    await input.trigger('keyup.enter')
    expect(replace).toHaveBeenCalledWith({ name: 'search', query: { q } })
  })

  it('快速翻页时先返回的旧页结果被序号丢弃', async () => {
    let resolvePage2
    let resolvePage3
    postApi.search
      .mockResolvedValueOnce({ content: [postPayload()], totalElements: 21, totalPages: 3 })
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolvePage2 = resolve
          })
      )
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolvePage3 = resolve
          })
      )
    route.query = { q: '全文' }
    route.fullPath = '/search?q=全文'

    const wrapper = mountSearch()
    await flush()

    await wrapper.find('.pager-next').trigger('click')
    await flush()
    await wrapper.find('.pager-next').trigger('click')
    await flush()

    resolvePage2({ content: [postPayload({ id: 2, title: '第二页旧' })], totalElements: 21, totalPages: 3 })
    await flush()
    expect(wrapper.text()).not.toContain('第二页旧')

    resolvePage3({ content: [postPayload({ id: 3, title: '第三页新' })], totalElements: 21, totalPages: 3 })
    await flush()
    expect(wrapper.text()).toContain('第三页新')
    expect(wrapper.find('[data-loading]').attributes('data-loading')).toBe('false')
  })
})
