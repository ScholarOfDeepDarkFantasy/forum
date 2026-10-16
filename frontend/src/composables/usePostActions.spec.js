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
    const actions = usePostActions()
    actions.syncFromPost({ id: 34, likeCount: 8, favoriteCount: 0 })

    // 注意：计数只在 await 成功后才写入，失败时不会中途改变，
    // 因此本用例实际锚定的是 liked 的回滚与「失败不改变已有状态」这一契约；
    // 计数回滚分支在当前实现下不可达（若将来改为乐观更新，此断言即会生效）。
    // 先成功一次把计数推到 9，再让下一次失败，使“失败后计数仍为 9”具备可判别性。
    postApi.like.mockResolvedValueOnce({ active: true, count: 9 })
    await actions.toggleLike(34)
    expect(actions.stateFor(34)).toMatchObject({ liked: true, likeCount: 9 })

    postApi.like.mockRejectedValueOnce(new Error('network'))
    await expect(actions.toggleLike(34)).rejects.toThrow('network')
    expect(actions.stateFor(34)).toMatchObject({ liked: true, likeCount: 9 })
  })

  it('收藏失败时回滚到原计数', async () => {
    const actions = usePostActions()
    actions.syncFromPost({ id: 37, likeCount: 0, favoriteCount: 4 })

    // 同「点赞失败时回滚到原计数」：计数回滚分支当前不可达，
    // 此处同样只锚定 favorited 的回滚与「失败不改变已有状态」。
    postApi.favorite.mockResolvedValueOnce({ active: true, count: 5 })
    await actions.toggleFavorite(37)
    expect(actions.stateFor(37)).toMatchObject({ favorited: true, favoriteCount: 5 })

    postApi.favorite.mockRejectedValueOnce(new Error('network'))
    await expect(actions.toggleFavorite(37)).rejects.toThrow('network')
    expect(actions.stateFor(37)).toMatchObject({ favorited: true, favoriteCount: 5 })
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

  it('同步服务端的 liked/favorited 标志（首次同步即生效）', () => {
    const actions = usePostActions()
    actions.syncFromPost({ id: 41, likeCount: 3, favoriteCount: 2, liked: true, favorited: true })
    expect(actions.stateFor(41)).toMatchObject({
      liked: true,
      favorited: true,
      likeCount: 3,
      favoriteCount: 2
    })
  })

  it('服务端标志为 false 时不要把本地状态当成 true', () => {
    const actions = usePostActions()
    actions.syncFromPost({ id: 42, likeCount: 0, favoriteCount: 0, liked: false, favorited: false })
    expect(actions.stateFor(42)).toMatchObject({ liked: false, favorited: false })
  })

  it('同步不含标志的响应（未登录/缺字段）不会凭空造出 true', () => {
    const actions = usePostActions()
    actions.syncFromPost({ id: 43, likeCount: 1, favoriteCount: 1 })
    expect(actions.stateFor(43)).toMatchObject({ liked: false, favorited: false })
  })

  it('用户已操作后，陈旧的同步不会把 liked/favorited 改回去', async () => {
    postApi.like.mockResolvedValue({ active: true, count: 4 })
    postApi.favorite.mockResolvedValue({ active: true, count: 3 })
    const actions = usePostActions()
    // 列表数据（用户操作前抓取）：未点赞、未收藏
    actions.syncFromPost({ id: 44, likeCount: 3, favoriteCount: 2, liked: false, favorited: false })

    await actions.toggleLike(44)
    await actions.toggleFavorite(44)
    expect(actions.stateFor(44)).toMatchObject({ liked: true, favorited: true })

    // 同一个陈旧列表负载再次到达：不得回退用户刚做的操作，也不得与计数自相矛盾
    actions.syncFromPost({ id: 44, likeCount: 3, favoriteCount: 2, liked: false, favorited: false })
    expect(actions.stateFor(44)).toMatchObject({
      liked: true,
      favorited: true,
      likeCount: 4,
      favoriteCount: 3
    })
  })

  it('刷新详情页（同 id、用户未操作）会用服务端标志覆盖默认的 false', () => {
    // 复现本任务要修的 bug：刷新后点赞按钮变灰。
    // 先经 stateFor 建立默认状态（模拟上一轮渲染已把 id 放进 stateMap），
    // 再让详情接口返回 liked/favorited=true，此时用户并未操作，标志必须被写入。
    const actions = usePostActions()
    expect(actions.stateFor(45)).toMatchObject({ liked: false, favorited: false })

    actions.syncFromPost({ id: 45, likeCount: 5, favoriteCount: 2, liked: true, favorited: true })
    expect(actions.stateFor(45)).toMatchObject({ liked: true, favorited: true })
  })

  it('用户取消点赞后，服务端返回的旧 true 不会把按钮重新点亮', async () => {
    postApi.like.mockResolvedValue({ active: false, count: 5 })
    const actions = usePostActions()
    actions.syncFromPost({ id: 46, likeCount: 6, favoriteCount: 0, liked: true })
    expect(actions.stateFor(46).liked).toBe(true)

    await actions.toggleLike(46)
    expect(actions.stateFor(46)).toMatchObject({ liked: false, likeCount: 5 })

    // 陈旧负载仍说 liked=true
    actions.syncFromPost({ id: 46, likeCount: 6, favoriteCount: 0, liked: true })
    expect(actions.stateFor(46).liked).toBe(false)
  })
})
