import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import UserCard from './UserCard.vue'
import BoardCard from './BoardCard.vue'

/**
 * 只验证「卡片渲染了哪些字段」与「交互发出哪些事件」。
 * 父组件如何响应事件（弹窗、确认框、请求）由 AdminView.spec.js 负责，
 * 这里不重复覆盖，也不引入 pinia / api mock。
 */
const stubs = {
  'el-tag': {
    props: ['type', 'size'],
    template: '<span class="tag" :data-type="type"><slot /></span>'
  },
  'el-button': {
    props: ['disabled'],
    emits: ['click'],
    template: '<button class="btn" :disabled="disabled" @click="$emit(\'click\')"><slot /></button>'
  }
}

const formatTime = (t) => (t ? String(t).replace('T', ' ').slice(0, 16) : '')

describe('UserCard', () => {
  const user = {
    id: 7,
    username: 'alice',
    nickname: '爱丽丝',
    email: 'alice@test.com',
    status: 'ACTIVE',
    mutedUntil: null,
    roles: ['USER']
  }

  const mountUser = (u = user) =>
    mount(UserCard, {
      props: { user: u, statusTag: () => 'success', formatTime },
      global: { stubs }
    })

  it('渲染用户的关键字段', () => {
    const text = mountUser().text()
    expect(text).toContain('alice')
    expect(text).toContain('爱丽丝')
    expect(text).toContain('alice@test.com')
    expect(text).toContain('ACTIVE')
    expect(text).toContain('USER')
  })

  it('昵称为空时显示占位符', () => {
    expect(mountUser({ ...user, nickname: '' }).text()).toContain('-')
  })

  it('禁言时间经过 formatTime 格式化，无值显示占位符', () => {
    expect(mountUser({ ...user, mutedUntil: '2026-01-01T10:20:30' }).text()).toContain('2026-01-01 10:20')
    expect(mountUser({ ...user, mutedUntil: null }).text()).toContain('-')
  })

  it('状态标签使用 statusTag 返回的类型', () => {
    const wrapper = mount(UserCard, {
      props: { user: { ...user, status: 'BANNED' }, statusTag: () => 'danger', formatTime },
      global: { stubs }
    })
    expect(wrapper.find('.tag').attributes('data-type')).toBe('danger')
  })

  it('管理员的状态管理按钮被禁用', () => {
    const wrapper = mountUser({ ...user, roles: ['ADMIN'] })
    expect(wrapper.find('.btn').attributes('disabled')).toBeDefined()
  })

  it('普通用户的状态管理按钮可用且点击发出 status 事件', async () => {
    const wrapper = mountUser()
    const btn = wrapper.find('.btn')
    expect(btn.attributes('disabled')).toBeUndefined()

    await btn.trigger('click')

    expect(wrapper.emitted('status')).toHaveLength(1)
    expect(wrapper.emitted('status')[0][0]).toEqual(user)
  })

  it('roles 缺失时不报错，按钮保持可用', () => {
    const wrapper = mountUser({ ...user, roles: undefined })
    expect(wrapper.find('.btn').attributes('disabled')).toBeUndefined()
  })
})

describe('BoardCard', () => {
  const board = { id: 3, name: '后端', description: 'Java 讨论', postCount: 12 }

  const mountBoard = (b = board) =>
    mount(BoardCard, { props: { board: b }, global: { stubs } })

  it('渲染板块字段', () => {
    const text = mountBoard().text()
    expect(text).toContain('后端')
    expect(text).toContain('Java 讨论')
    expect(text).toContain('12')
  })

  it('描述为空时显示占位符', () => {
    expect(mountBoard({ ...board, description: null }).text()).toContain('-')
  })

  it('编辑与删除按钮分别发出事件', async () => {
    const wrapper = mountBoard()
    const btns = wrapper.findAll('.btn')
    expect(btns).toHaveLength(2)

    await btns[0].trigger('click')
    await btns[1].trigger('click')

    expect(wrapper.emitted('edit')[0][0]).toEqual(board)
    expect(wrapper.emitted('remove')[0][0]).toEqual(board)
  })
})
