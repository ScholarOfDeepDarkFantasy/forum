import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const { uploadAvatar, removeAvatar, warning, success } = vi.hoisted(() => ({
  uploadAvatar: vi.fn(),
  removeAvatar: vi.fn(),
  warning: vi.fn(),
  success: vi.fn()
}))

const push = vi.fn()
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {}, name: 'profile', fullPath: '/profile', path: '/profile' }),
  useRouter: () => ({ push })
}))

vi.mock('@/api', () => ({
  postApi: {
    mine: vi.fn(),
    myFavorites: vi.fn(),
    remove: vi.fn()
  },
  userApi: { uploadAvatar, removeAvatar }
}))

vi.mock('element-plus', () => ({ ElMessage: { success, warning, error: vi.fn() } }))

import ProfileView from './ProfileView.vue'
import { postApi } from '@/api'
import { useAuthStore } from '@/stores/auth'

const stubs = {
  'el-card': { template: '<div><slot /></div>' },
  'el-tabs': { template: '<div><slot /></div>' },
  // 给每个 tab 打上 name 类名：el-tabs 被打桩后所有 pane 会平铺渲染，
  // 不区分 pane 就无法断言「收藏」这个 pane 自己的空状态。
  'el-tab-pane': {
    props: ['name'],
    template: '<div class="pane" :class="`pane-${name}`"><slot /></div>'
  },
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { template: '<div><slot /></div>' },
  'el-input': { template: '<input />' },
  'el-button': {
    props: ['loading', 'disabled', 'text', 'type'],
    template: '<button :disabled="disabled" :data-loading="loading"><slot /></button>'
  },
  'el-pagination': { template: '<div class="pager-stub" />' },
  'el-empty': { template: '<div class="empty-stub" />' },
  'el-avatar': { props: ['size', 'src'], template: '<div class="avatar-stub" />' }
}

let pinia

function mountProfile(options = {}) {
  return mount(ProfileView, {
    global: {
      plugins: [pinia],
      stubs,
      directives: { loading: () => {} },
      config: options.onError ? { errorHandler: options.onError } : {}
    }
  })
}

function flush() {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

/** 后端 /posts/favorites/mine 返回的是 PageResponse，不是裸数组 */
function page(rows) {
  return { content: rows, page: 0, size: 10, totalElements: rows.length, totalPages: rows.length ? 1 : 0, last: true }
}

/** el-tabs 被打桩后三个 pane 会平铺渲染，断言必须限定在收藏这个 pane 内 */
function favPane(wrapper) {
  return wrapper.find('.pane-favorites')
}

describe('ProfileView 我的收藏', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
    useAuthStore().user = { id: 1, username: 'alice', nickname: '爱丽丝', roles: ['USER'] }
    postApi.mine.mockResolvedValue(page([]))
    postApi.myFavorites.mockResolvedValue(page([]))
  })

  it('把收藏接口的 content 渲染成列表', async () => {
    postApi.myFavorites.mockResolvedValue(
      page([
        { id: 11, title: '被收藏的帖子', boardName: 'Java技术', createTime: '2026-10-17T10:00:00' },
        { id: 12, title: '第二篇', boardName: '闲聊', createTime: '2026-10-16T09:00:00' }
      ])
    )
    const wrapper = mountProfile()
    await flush()

    const titles = favPane(wrapper).findAll('.row-title').map((n) => n.text())
    expect(titles).toEqual(['被收藏的帖子', '第二篇'])
    expect(favPane(wrapper).find('.empty-stub').exists()).toBe(false)
  })

  it('点击收藏条目跳转到帖子详情', async () => {
    postApi.myFavorites.mockResolvedValue(
      page([{ id: 21, title: '帖子', boardName: 'Board', createTime: '2026-10-17T10:00:00' }])
    )
    const wrapper = mountProfile()
    await flush()

    await favPane(wrapper).find('.row-item').trigger('click')

    expect(push).toHaveBeenCalledWith('/posts/21')
  })

  it('收藏为空时显示空状态', async () => {
    const wrapper = mountProfile()
    await flush()

    expect(favPane(wrapper).find('.empty-stub').exists()).toBe(true)
    expect(favPane(wrapper).findAll('.row-item')).toHaveLength(0)
  })

  it('收藏接口失败时降级为空列表而不是把页面搞崩', async () => {
    postApi.myFavorites.mockRejectedValue(new Error('boom'))
    const wrapper = mountProfile()
    await flush()

    expect(favPane(wrapper).find('.empty-stub').exists()).toBe(true)
  })

  it('请求收藏时带上分页参数（否则后端默认页与前端页码会脱节）', async () => {
    mountProfile()
    await flush()

    expect(postApi.myFavorites).toHaveBeenCalledWith(expect.objectContaining({ page: 0, size: 10 }))
  })

  it('收藏超过一页时渲染分页器', async () => {
    postApi.myFavorites.mockResolvedValue({
      content: [{ id: 31, title: '帖子', boardName: 'Board', createTime: '2026-10-17T10:00:00' }],
      page: 0,
      size: 10,
      totalElements: 25,
      totalPages: 3,
      last: false
    })
    const wrapper = mountProfile()
    await flush()

    expect(favPane(wrapper).find('.pager-stub').exists()).toBe(true)
  })
})

describe('ProfileView 头像上传', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.user = {
      id: 1,
      username: 'alice',
      nickname: '爱丽丝',
      avatarUrl: '/uploads/avatars/old.jpg',
      roles: ['USER']
    }
    auth.fetchMe = vi.fn().mockResolvedValue()
    postApi.mine.mockResolvedValue(page([]))
    postApi.myFavorites.mockResolvedValue(page([]))
    uploadAvatar.mockResolvedValue({ avatarUrl: '/uploads/avatars/new.jpg' })
    removeAvatar.mockResolvedValue({ avatarUrl: null })
  })

  async function pick(wrapper, file) {
    const input = wrapper.find('input[type="file"]')
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')
    await flush()
  }

  it('上传合法 JPEG 调用 userApi.uploadAvatar 并刷新用户', async () => {
    const wrapper = mountProfile()
    await flush()
    const file = new File(['x'], 'me.jpg', { type: 'image/jpeg' })

    await pick(wrapper, file)

    expect(uploadAvatar).toHaveBeenCalledWith(file)
    expect(useAuthStore().fetchMe).toHaveBeenCalled()
    expect(success).toHaveBeenCalledWith('头像已更新')
  })

  it('非法类型不发请求并提示', async () => {
    const wrapper = mountProfile()
    await flush()

    await pick(wrapper, new File(['x'], 'me.gif', { type: 'image/gif' }))

    expect(uploadAvatar).not.toHaveBeenCalled()
    expect(warning).toHaveBeenCalledWith('仅支持 JPEG/PNG 图片')
  })

  it('超过 2MB 不发请求并提示', async () => {
    const wrapper = mountProfile()
    await flush()
    const big = new File([new Uint8Array(2 * 1024 * 1024 + 1)], 'big.jpg', { type: 'image/jpeg' })

    await pick(wrapper, big)

    expect(uploadAvatar).not.toHaveBeenCalled()
    expect(warning).toHaveBeenCalledWith('图片不能超过 2MB')
  })

  it('移除头像调用 userApi.removeAvatar 并刷新用户', async () => {
    const wrapper = mountProfile()
    await flush()

    await wrapper.find('.avatar-remove').trigger('click')
    await flush()

    expect(removeAvatar).toHaveBeenCalled()
    expect(useAuthStore().fetchMe).toHaveBeenCalled()
    expect(success).toHaveBeenCalledWith('头像已移除')
  })

  it('没有头像时不渲染移除按钮', async () => {
    useAuthStore().user = { id: 1, username: 'alice', nickname: '爱丽丝', roles: ['USER'] }
    const wrapper = mountProfile()
    await flush()

    expect(wrapper.find('.avatar-remove').exists()).toBe(false)
  })

  it('校验失败后清空 input.value，同一文件可以再次触发 change', async () => {
    const wrapper = mountProfile()
    await flush()
    const gif = new File(['x'], 'me.gif', { type: 'image/gif' })

    await pick(wrapper, gif)
    expect(warning).toHaveBeenCalledTimes(1)
    expect(wrapper.find('input[type="file"]').element.value).toBe('')

    // 若 value 没清空，浏览器第二次选同一个文件不会派发 change，用户会以为没反应
    await pick(wrapper, gif)
    expect(warning).toHaveBeenCalledTimes(2)
    expect(uploadAvatar).not.toHaveBeenCalled()
  })

  it('file.type 为空时按扩展名回退，不误杀合法 PNG（后端按 magic bytes 校验）', async () => {
    const wrapper = mountProfile()
    await flush()

    await pick(wrapper, new File(['x'], 'ME.PNG', { type: '' }))

    expect(uploadAvatar).toHaveBeenCalled()
    expect(warning).not.toHaveBeenCalled()
  })

  it('file.type 为空且扩展名不是图片时仍然拒绝', async () => {
    const wrapper = mountProfile()
    await flush()

    await pick(wrapper, new File(['x'], 'me.gif', { type: '' }))

    expect(uploadAvatar).not.toHaveBeenCalled()
    expect(warning).toHaveBeenCalledWith('仅支持 JPEG/PNG 图片')
  })

  it('上传失败时不提示成功、复位 loading，且异常被就地捕获（不抛给 Vue）', async () => {
    const errors = []
    uploadAvatar.mockRejectedValue(new Error('超时'))
    const wrapper = mountProfile({ onError: (err) => errors.push(err) })
    await flush()

    await pick(wrapper, new File(['x'], 'me.jpg', { type: 'image/jpeg' }))

    expect(errors).toEqual([])
    expect(success).not.toHaveBeenCalledWith('头像已更新')
    expect(wrapper.find('.avatar-actions button').attributes('data-loading')).toBe('false')
    expect(useAuthStore().fetchMe).not.toHaveBeenCalled()
  })

  it('移除失败时不提示成功、复位状态，且异常被就地捕获（不抛给 Vue）', async () => {
    const errors = []
    removeAvatar.mockRejectedValue(new Error('boom'))
    const wrapper = mountProfile({ onError: (err) => errors.push(err) })
    await flush()

    await wrapper.find('.avatar-remove').trigger('click')
    await flush()

    expect(errors).toEqual([])
    expect(success).not.toHaveBeenCalledWith('头像已移除')
    expect(wrapper.find('.avatar-remove').attributes('data-loading')).toBe('false')
  })

  it('上传进行中禁用移除按钮，避免并发请求把刚更新的头像又移掉', async () => {
    let resolveUpload
    uploadAvatar.mockReturnValue(new Promise((resolve) => { resolveUpload = resolve }))
    const wrapper = mountProfile()
    await flush()

    const input = wrapper.find('input[type="file"]')
    Object.defineProperty(input.element, 'files', {
      value: [new File(['x'], 'me.jpg', { type: 'image/jpeg' })],
      configurable: true
    })
    await input.trigger('change')

    expect(uploadAvatar).toHaveBeenCalled()
    expect(wrapper.find('.avatar-remove').attributes('disabled')).toBeDefined()

    resolveUpload({})
    await flush()
    expect(wrapper.find('.avatar-remove').attributes('disabled')).toBeUndefined()
  })

  it('移除进行中禁用选择按钮，避免并发上传与移除互相回滚', async () => {
    let resolveRemove
    removeAvatar.mockReturnValue(new Promise((resolve) => { resolveRemove = resolve }))
    const wrapper = mountProfile()
    await flush()

    await wrapper.find('.avatar-remove').trigger('click')

    expect(removeAvatar).toHaveBeenCalled()
    expect(wrapper.find('.avatar-actions button').attributes('disabled')).toBeDefined()

    resolveRemove({})
    await flush()
    expect(wrapper.find('.avatar-actions button').attributes('disabled')).toBeUndefined()
  })
})
