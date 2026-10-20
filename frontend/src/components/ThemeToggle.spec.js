import { describe, it, expect, beforeEach, vi } from 'vitest'
import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import { Moon, MoonNight, Sunny } from '@element-plus/icons-vue'
import ThemeToggle from './ThemeToggle.vue'
import { useTheme } from '@/composables/useTheme'

const stubs = {
  'el-dropdown': {
    name: 'ElDropdown',
    emits: ['command'],
    template: '<div class="dropdown-stub"><slot /><slot name="dropdown" /></div>'
  },
  'el-dropdown-menu': {
    name: 'ElDropdownMenu',
    template: '<div class="menu-stub"><slot /></div>'
  },
  'el-dropdown-item': {
    name: 'ElDropdownItem',
    props: ['command'],
    template: '<div class="menu-item"><slot /></div>'
  },
  'el-button': {
    name: 'ElButton',
    props: ['icon', 'circle', 'text'],
    template: '<button><slot /></button>'
  },
  'el-icon': { name: 'ElIcon', template: '<i><slot /></i>' }
}

function mountToggle() {
  return mount(ThemeToggle, { global: { stubs } })
}

beforeEach(async () => {
  localStorage.clear()
  document.documentElement.className = ''
  document.documentElement.style.colorScheme = ''
  useTheme().setMode('system')
  await nextTick()
})

describe('ThemeToggle', () => {
  it('渲染三个菜单项，触发按钮带 aria-label', () => {
    const wrapper = mountToggle()
    expect(wrapper.findAll('.menu-item')).toHaveLength(3)
    expect(wrapper.text()).toContain('跟随系统')
    expect(wrapper.text()).toContain('浅色')
    expect(wrapper.text()).toContain('深色')
    expect(wrapper.find('button').attributes('aria-label')).toBe('切换主题')
  })

  it('默认为 system：当前项打勾，按钮图标是 MoonNight', () => {
    const wrapper = mountToggle()
    expect(wrapper.find('.is-active').text()).toBe('跟随系统')
    expect(wrapper.findComponent({ name: 'ElButton' }).props('icon')).toBe(MoonNight)
  })

  it('选择深色后写入 localStorage、挂 dark 类并切换图标', async () => {
    const wrapper = mountToggle()
    wrapper.findComponent({ name: 'ElDropdown' }).vm.$emit('command', 'dark')
    await nextTick()
    expect(localStorage.getItem('jf_theme')).toBe('dark')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    expect(wrapper.find('.is-active').text()).toBe('深色')
    expect(wrapper.findComponent({ name: 'ElButton' }).props('icon')).toBe(Moon)
  })

  it('选择浅色后移除 dark 类并保存 light', async () => {
    const wrapper = mountToggle()
    wrapper.findComponent({ name: 'ElDropdown' }).vm.$emit('command', 'dark')
    await nextTick()
    wrapper.findComponent({ name: 'ElDropdown' }).vm.$emit('command', 'light')
    await nextTick()
    expect(localStorage.getItem('jf_theme')).toBe('light')
    expect(document.documentElement.classList.contains('dark')).toBe(false)
    expect(wrapper.findComponent({ name: 'ElButton' }).props('icon')).toBe(Sunny)
  })

  it('选择跟随系统后删除 localStorage 键', async () => {
    const wrapper = mountToggle()
    wrapper.findComponent({ name: 'ElDropdown' }).vm.$emit('command', 'dark')
    await nextTick()
    wrapper.findComponent({ name: 'ElDropdown' }).vm.$emit('command', 'system')
    await nextTick()
    expect(localStorage.getItem('jf_theme')).toBeNull()
    expect(wrapper.find('.is-active').text()).toBe('跟随系统')
  })
})
