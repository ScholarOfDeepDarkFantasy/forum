import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'

// AdminView 自身不依赖 vue-router（路由守卫在 router/index.js 里），故无需 mock 路由。
// 只 mock 接口层：否则 onMounted 会发真实 axios 请求。
vi.mock('@/api', () => ({
  boardApi: { list: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn() },
  adminApi: { users: vi.fn(), changeStatus: vi.fn() }
}))

// 只替换 ElMessageBox：删板块会弹真实确认框并等一个永不 resolve 的 Promise，
// 测试里必须拦掉。ElMessage 保留原实现（不会渲染 DOM，只 push 通知）。
vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal()
  return {
    ...actual,
    ElMessageBox: { confirm: vi.fn(() => Promise.resolve()) }
  }
})

// 必须写全 .vue 后缀：同目录下的 AdminView.spec.js 会与 AdminView.vue 争抢
// 无扩展名的 "./AdminView" 解析，Vite 不会自动补 .vue。
import AdminView from './AdminView.vue'
import { boardApi, adminApi } from '@/api'

/**
 * 可手动驱动的 matchMedia 桩。
 *
 * jsdom 不实现 window.matchMedia，useResponsive 会走 `window.matchMedia` 缺失
 * 的分支直接返回 false —— 这样窄屏分支永远测不到。这里提供一个受控实现，
 * 并把 addEventListener 收到的回调存下来，供用例手动触发 change：
 * 这正是 useResponsive 自身用例覆盖不到、而本用例存在的理由。
 */
function installMatchMedia(matches) {
  const listeners = new Set()
  const mql = {
    matches,
    media: `(max-width: 768px)`,
    addEventListener: vi.fn((type, cb) => {
      if (type === 'change') listeners.add(cb)
    }),
    removeEventListener: vi.fn((type, cb) => {
      if (type === 'change') listeners.delete(cb)
    })
  }
  // 每次调用返回同一个对象，便于断言 add/remove 用的是同一个 mql 与同一个回调
  window.matchMedia = vi.fn(() => mql)
  return {
    mql,
    /** 模拟视口跨越断点：触发所有已注册的 change 回调 */
    fire(matches) {
      mql.matches = matches
      listeners.forEach((cb) => cb({ matches, media: mql.media }))
    },
    listenerCount: () => listeners.size
  }
}

/** 只渲染结构，Element Plus 组件全部打桩，避免引入 EP 的样式与副作用 */
const stubs = {
  'el-card': { template: '<div class="stub-card"><slot /></div>' },
  'el-tabs': { template: '<div><slot /></div>' },
  'el-tab-pane': { template: '<div class="stub-pane"><slot /></div>' },
  'el-table': {
    props: ['data'],
    template: '<table class="stub-table"><tr v-for="(row, i) in data" :key="i" class="stub-row"><slot /></tr></table>'
  },
  'el-table-column': { template: '<div />' },
  'el-tag': { template: '<span class="stub-tag"><slot /></span>' },
  'el-empty': { props: ['description'], template: '<div class="stub-empty">{{ description }}</div>' },
  'el-button': {
    props: ['disabled'],
    template: '<button :disabled="disabled"><slot /></button>'
  },
  'el-pagination': { template: '<div />' },
  // 必须尊重 modelValue：否则弹窗关闭时内容也在 DOM 里，
  // 「点击卡片后弹窗才打开」这类断言会永远为真、失去意义。
  // title 也渲染出来，才能验证弹窗拿到的是哪个目标。
  'el-dialog': {
    props: ['modelValue', 'title'],
    template:
      '<div v-if="modelValue" class="stub-dialog"><div class="stub-dialog-title">{{ title }}</div><slot /></div>'
  }
}

/** 两个对话框里的表单控件：同样打桩，否则 Vue 会刷「Failed to resolve component」告警 */
const formStubs = {
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { template: '<div><slot /></div>' },
  'el-input': { template: '<input />' },
  'el-input-number': { template: '<input type="number" />' },
  'el-radio-group': { template: '<div><slot /></div>' },
  'el-radio': { template: '<label><slot /></label>' }
}

const BOARDS = [
  { id: 1, name: '后端', description: 'Java 相关', sortOrder: 1, postCount: 3 },
  { id: 2, name: '前端', description: 'Vue 相关', sortOrder: 2, postCount: 5 }
]
const USERS = {
  content: [
    { id: 1, username: 'alice', email: 'a@x.com', nickname: '爱丽丝', status: 'ACTIVE', mutedUntil: null, roles: ['USER'] },
    { id: 2, username: 'boss', email: 'b@x.com', nickname: '', status: 'MUTED', mutedUntil: '2026-01-01T10:20:30', roles: ['ADMIN'] }
  ],
  totalElements: 2,
  totalPages: 1
}

let pinia
let mm

function mountAdmin() {
  // 复用同一个 pinia 实例（与 HomeView.spec.js 同因）：mount 若再 createPinia()，
  // 组件取到的 store 与本用例配置的就不是同一个对象。
  return mount(AdminView, { global: { plugins: [pinia], stubs: { ...stubs, ...formStubs } } })
}

/** 清空微任务队列，等待 onMounted 里的 await 链结束 */
function flush() {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

/** 挂载并等两个列表加载完成，此后断言 DOM 分支才是稳定的 */
async function mountReady() {
  const wrapper = mountAdmin()
  await flush()
  return wrapper
}

describe('AdminView 响应式渲染', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    pinia = createPinia()
    setActivePinia(pinia)
    boardApi.list.mockResolvedValue(BOARDS)
    adminApi.users.mockResolvedValue(USERS)
  })

  afterEach(() => {
    delete window.matchMedia
  })

  describe('桌面（matchMedia.matches = false）', () => {
    beforeEach(() => {
      mm = installMatchMedia(false)
    })

    it('渲染表格而非卡片，且表格收到的是列表接口返回的真实数据', async () => {
      const wrapper = await mountReady()

      const tables = wrapper.findAll('.stub-table')
      expect(tables).toHaveLength(2)
      // 桩把 :data 渲染成行数，桌面分支的数据流才被真正断言（原先桩吞掉了 data）
      expect(tables[0].findAll('.stub-row')).toHaveLength(BOARDS.length)
      expect(tables[1].findAll('.stub-row')).toHaveLength(USERS.content.length)
      expect(wrapper.find('.board-cards').exists()).toBe(false)
      expect(wrapper.find('.user-cards').exists()).toBe(false)
    })
  })

  describe('窄屏（matchMedia.matches = true）', () => {
    beforeEach(() => {
      mm = installMatchMedia(true)
    })

    it('板块与用户都渲染卡片而非表格', async () => {
      const wrapper = await mountReady()

      expect(wrapper.find('.board-cards').exists()).toBe(true)
      expect(wrapper.find('.user-cards').exists()).toBe(true)
      expect(wrapper.find('.stub-table').exists()).toBe(false)
    })

    it('卡片逐行呈现字段与标签', async () => {
      const wrapper = await mountReady()

      expect(wrapper.findAll('.card-row').length).toBeGreaterThan(0)

      const boardCard = wrapper.findAll('.board-card')[0]
      expect(boardCard.text()).toContain('后端')
      expect(boardCard.text()).toContain('Java 相关')

      const userCard = wrapper.findAll('.user-card')[0]
      expect(userCard.text()).toContain('alice')
      expect(userCard.text()).toContain('a@x.com')
      expect(userCard.text()).toContain('爱丽丝')
      expect(userCard.text()).toContain('ACTIVE')
    })

    it('用户卡片保留 ADMIN 禁用规则，及板块卡片的编辑/删除入口', async () => {
      const wrapper = await mountReady()

      // 用户卡片上的「状态管理」按钮：ADMIN 行禁用、普通行可用（与表格列同规则）
      const adminCard = wrapper.findAll('.user-card').find((c) => c.text().includes('boss'))
      expect(adminCard.find('button').attributes('disabled')).toBeDefined()
      const userCard = wrapper.findAll('.user-card')[0]
      expect(userCard.find('button').attributes('disabled')).toBeUndefined()

      // 板块卡片保留编辑与删除两个动作
      const boardCard = wrapper.findAll('.board-card')[0]
      const labels = boardCard.findAll('button').map((b) => b.text())
      expect(labels).toEqual(['编辑', '删除'])
    })

    it('列表为空时卡片分支渲染空状态而非一片空白', async () => {
      boardApi.list.mockResolvedValue([])
      adminApi.users.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0 })
      const wrapper = await mountReady()

      const empties = wrapper.findAll('.stub-empty')
      expect(empties).toHaveLength(2)
      // 桩把 description 渲染成文本，空状态确实带上了文案
      expect(empties[0].text()).toBe('暂无板块')
      expect(empties[1].text()).toBe('暂无用户')

      // 空列表下不应残留任何卡片
      expect(wrapper.findAll('.board-card')).toHaveLength(0)
      expect(wrapper.findAll('.user-card')).toHaveLength(0)
      // 卡片容器仍在（空状态渲染在容器内），窄屏分支结构不变
      expect(wrapper.find('.board-cards').exists()).toBe(true)
      expect(wrapper.find('.user-cards').exists()).toBe(true)
    })

    it('列表非空时不渲染空状态', async () => {
      const wrapper = await mountReady()

      expect(wrapper.findAll('.board-card')).toHaveLength(BOARDS.length)
      expect(wrapper.findAll('.stub-empty')).toHaveLength(0)
    })

    // 卡片抽成子组件后，事件要跨组件边界传回父组件的处理函数。
    // 若 @edit / @remove / @status 的监听名与子组件 emit 名不一致，按钮会静默失效
    // —— 结构断言（按钮存在、文案正确）发现不了这种坏，必须点下去看副作用。
    it('点击板块卡片的编辑按钮打开编辑弹窗', async () => {
      const wrapper = await mountReady()

      const boardCard = wrapper.findAll('.board-card')[0]
      await boardCard.findAll('button')[0].trigger('click')
      await flush()

      expect(wrapper.find('.stub-dialog-title').text()).toBe('编辑板块')
    })

    it('点击板块卡片的删除按钮调用删除接口', async () => {
      const wrapper = await mountReady()
      boardApi.remove.mockResolvedValue({})

      const boardCard = wrapper.findAll('.board-card')[0]
      await boardCard.findAll('button')[1].trigger('click')
      await flush()

      expect(boardApi.remove).toHaveBeenCalledWith(BOARDS[0].id)
    })

    it('点击用户卡片的状态管理按钮打开状态弹窗并带上目标用户', async () => {
      const wrapper = await mountReady()

      const userCard = wrapper.findAll('.user-card')[0]
      await userCard.find('button').trigger('click')
      await flush()

      // 弹窗标题模板里插了 statusTarget.username，能直接证明事件把 user 传上来了
      expect(wrapper.find('.stub-dialog-title').text()).toBe('管理用户：alice')
    })
  })

  describe('视口变化', () => {
    it('宽度跨越断点时响应 change 事件切换 DOM 结构', async () => {
      mm = installMatchMedia(false)
      const wrapper = await mountReady()
      expect(wrapper.find('.user-cards').exists()).toBe(false)

      // 抓手：把 useResponsive 注册到 matchMedia 上的回调取出来手动触发，
      // 这是 jsdom 里唯一能驱动 change 的途径（视口本身不会真变）
      expect(mm.listenerCount()).toBe(1)
      mm.fire(true)
      await nextTick()

      expect(wrapper.find('.board-cards').exists()).toBe(true)
      expect(wrapper.find('.user-cards').exists()).toBe(true)
      expect(wrapper.find('.stub-table').exists()).toBe(false)

      // 反向切回桌面也应生效，避免只实现了单向
      mm.fire(false)
      await nextTick()

      expect(wrapper.find('.user-cards').exists()).toBe(false)
      expect(wrapper.findAll('.stub-table')).toHaveLength(2)
    })

    it('卸载时移除同一个 change 回调，避免监听泄漏', async () => {
      mm = installMatchMedia(true)
      const wrapper = await mountReady()

      const [addedType, addedCb] = mm.mql.addEventListener.mock.calls[0]
      expect(addedType).toBe('change')
      expect(mm.mql.removeEventListener).not.toHaveBeenCalled()

      wrapper.unmount()

      expect(mm.mql.removeEventListener).toHaveBeenCalledWith('change', addedCb)
    })
  })
})
