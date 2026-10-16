import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('@/api', () => ({
  postApi: {
    like: vi.fn(),
    favorite: vi.fn()
  }
}))

import { postApi } from '@/api'
import { usePostActions } from './usePostActions'

describe('usePostActions', () => {
  beforeEach(() => vi.clearAllMocks())

  // 每个用例使用独立 postId：stateMap 是模块级共享状态，且 syncFromPost 有意不覆盖
  // 非零计数（避免服务端列表数据盖掉用户刚做的操作），同一 id 会让用例相互污染。
  // 请勿把这些 id 合并成一个——否则用例会依赖执行顺序。

  it('初始状态来自传入的帖子对象', () => {
    const actions = usePostActions()
    actions.syncFromPost({ id: 31, likeCount: 2, favoriteCount: 1 })
    expect(actions.stateFor(31)).toMatchObject({ likeCount: 2, favoriteCount: 1 })
  })

  it('点赞成功后写入返回的计数并标记 liked', async () => {
    postApi.like.mockResolvedValue({ active: true, count: 9 })
    const actions = usePostActions()
    actions.syncFromPost({ id: 32, likeCount: 8, favoriteCount: 0 })
    await actions.toggleLike(32)
    expect(actions.stateFor(32)).toMatchObject({ liked: true, likeCount: 9 })
  })

  it('取消点赞后标记 liked 为 false', async () => {
    postApi.like.mockResolvedValue({ active: false, count: 7 })
    const actions = usePostActions()
    actions.syncFromPost({ id: 33, likeCount: 8, favoriteCount: 0 })
    await actions.toggleLike(33)
    expect(actions.stateFor(33)).toMatchObject({ liked: false, likeCount: 7 })
  })

  it('点赞失败时回滚到原计数', async () => {
    postApi.like.mockRejectedValue(new Error('network'))
    const actions = usePostActions()
    actions.syncFromPost({ id: 34, likeCount: 8, favoriteCount: 0 })
    await expect(actions.toggleLike(34)).rejects.toThrow('network')
    expect(actions.stateFor(34)).toMatchObject({ liked: false, likeCount: 8 })
  })

  it('收藏成功后写入返回的计数并标记 favorited', async () => {
    postApi.favorite.mockResolvedValue({ active: true, count: 5 })
    const actions = usePostActions()
    actions.syncFromPost({ id: 35, likeCount: 0, favoriteCount: 4 })
    await actions.toggleFavorite(35)
    expect(actions.stateFor(35)).toMatchObject({ favorited: true, favoriteCount: 5 })
  })

  it('再次同步同一帖子的陈旧计数不会覆盖本地已变更的计数', async () => {
    postApi.like.mockResolvedValue({ active: true, count: 9 })
    const actions = usePostActions()
    actions.syncFromPost({ id: 36, likeCount: 8, favoriteCount: 0 })
    await actions.toggleLike(36)
    expect(actions.stateFor(36).likeCount).toBe(9)

    // 服务端列表数据仍是旧值 8，不应把用户刚操作的 9 改回去
    actions.syncFromPost({ id: 36, likeCount: 8, favoriteCount: 0 })
    expect(actions.stateFor(36).likeCount).toBe(9)
  })
})
