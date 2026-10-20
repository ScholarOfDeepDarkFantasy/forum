import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import UserAvatar from './UserAvatar.vue'

const stubs = {
  'el-avatar': {
    name: 'ElAvatar',
    props: ['size', 'src'],
    emits: ['error'],
    template: '<div class="avatar-stub" :data-src="src"><slot /></div>'
  }
}

function mountAvatar(props) {
  return mount(UserAvatar, { props, global: { stubs } })
}

describe('UserAvatar', () => {
  it('有 url 时把解析后的地址交给 el-avatar', () => {
    const wrapper = mountAvatar({ url: '/uploads/avatars/a.jpg', name: '爱丽丝' })

    expect(wrapper.find('.avatar-stub').attributes('data-src')).toBe('/uploads/avatars/a.jpg')
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
})
