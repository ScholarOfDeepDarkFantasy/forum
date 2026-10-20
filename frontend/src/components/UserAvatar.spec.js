import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import UserAvatar from './UserAvatar.vue'

const stubs = {
  'el-avatar': {
    name: 'ElAvatar',
    props: ['size', 'src', 'alt'],
    emits: ['error'],
    template: '<div class="avatar-stub" :data-src="src" :data-alt="alt"><slot /></div>'
  }
}

function mountAvatar(props) {
  return mount(UserAvatar, { props, global: { stubs } })
}

describe('UserAvatar', () => {
  it('有 url 时把解析后的地址交给 el-avatar', () => {
    const wrapper = mountAvatar({ url: '/uploads/avatars/a.jpg', name: '爱丽丝' })

    expect(wrapper.find('.avatar-stub').attributes('data-src')).toBe('/uploads/avatars/a.jpg')
    // 图片与字母回退共用同一套读屏名称，避免同一用户在不同状态下播报不一致
    expect(wrapper.find('.avatar-stub').attributes('data-alt')).toBe('爱丽丝的头像')
    expect(wrapper.findComponent({ name: 'ElAvatar' }).props('size')).toBe(28)
  })

  it('size 透传给 el-avatar', () => {
    const wrapper = mountAvatar({ url: '', name: 'bob', size: 64 })

    expect(wrapper.findComponent({ name: 'ElAvatar' }).props('size')).toBe(64)
  })

  it('外链头像原样透传', () => {
    const wrapper = mountAvatar({ url: 'https://cdn.example.com/a.png', name: 'bob' })

    expect(wrapper.find('.avatar-stub').attributes('data-src')).toBe('https://cdn.example.com/a.png')
  })

  it('无 url 时只显示首字母', () => {
    const wrapper = mountAvatar({ url: '', name: '爱丽丝' })

    expect(wrapper.find('.avatar-stub').attributes('data-src')).toBeUndefined()
    expect(wrapper.text()).toBe('爱')
  })

  it('图片加载失败后回退首字母', async () => {
    const wrapper = mountAvatar({ url: '/uploads/avatars/a.jpg', name: 'bob' })

    wrapper.findComponent({ name: 'ElAvatar' }).vm.$emit('error')
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.avatar-stub').attributes('data-src')).toBeUndefined()
    expect(wrapper.text()).toBe('b')
  })

  it('name 为空时回退问号', () => {
    const wrapper = mountAvatar({ url: '', name: '' })

    expect(wrapper.text()).toBe('?')
  })

  it('字母头像整体带 img 角色与名称，字母本身对读屏隐藏（避免与 aria-label 重复播报）', () => {
    const wrapper = mountAvatar({ url: '', name: '爱丽丝' })

    const fallback = wrapper.find('[role="img"]')
    expect(fallback.attributes('aria-label')).toBe('爱丽丝的头像')
    expect(fallback.text()).toBe('爱')
    expect(wrapper.find('[aria-hidden="true"]').text()).toBe('爱')
  })

  it('name 为空时 aria-label 回退为「用户头像」', () => {
    const wrapper = mountAvatar({ url: '', name: '' })

    expect(wrapper.find('[role="img"]').attributes('aria-label')).toBe('用户头像')
  })

  it('代理对（emoji）昵称只取一个完整码点，不切出半个字符', () => {
    const wrapper = mountAvatar({ url: '', name: '😀小张' })

    expect(wrapper.text()).toBe('😀')
  })

  it('url 变化后清除失败状态，重新尝试显示图片', async () => {
    const wrapper = mountAvatar({ url: '/uploads/avatars/a.jpg', name: 'bob' })

    wrapper.findComponent({ name: 'ElAvatar' }).vm.$emit('error')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.avatar-stub').attributes('data-src')).toBeUndefined()

    await wrapper.setProps({ url: '/uploads/avatars/b.jpg' })

    expect(wrapper.find('.avatar-stub').attributes('data-src')).toBe('/uploads/avatars/b.jpg')
  })
})
