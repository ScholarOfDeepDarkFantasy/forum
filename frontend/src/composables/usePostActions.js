import { reactive } from 'vue'
import { postApi } from '@/api'

// 模块级共享：首页列表与详情页读写同一份状态，保证点赞/收藏显示一致
const stateMap = reactive({})

/**
 * 本会话内用户已手动操作过的帖子 id。
 *
 * 布尔标志没有 likeCount===0 那样的「未同步」哨兵可借（false 是完全合法的值），
 * 所以用一个显式脏标记区分两种同步场景：
 * - 用户未操作过：服务端数据是权威的（刷新详情页要能点亮已点赞的按钮）；
 * - 用户已操作过：本地结果比服务端新，陈旧的列表负载不得回退它。
 * 标记只由 toggleLike/toggleFavorite 写入，不由 syncFromPost 写入——
 * 这正是「陈旧响应」与「刷新重取」的唯一区别。
 */
const locallyToggled = new Set()

/**
 * 帖子互动状态：以 postId 为键维护 like/favorite 状态与计数。
 */
export function usePostActions() {
  function ensure(postId) {
    if (!stateMap[postId]) {
      stateMap[postId] = { liked: false, likeCount: 0, favorited: false, favoriteCount: 0 }
    }
    return stateMap[postId]
  }

  /**
   * 用后端返回的帖子数据初始化共享状态。
   *
   * 计数：沿用既有哨兵规则，仅当本地为 0 且服务端给了非 null 值时才写入。
   * 该哨兵存在已知缺陷——真实为 0 赞的帖子无法与「从未同步」区分，首次同步后
   * 便不再接受服务端计数。本次未改动它：改动会影响首页列表等既有调用方，
   * 且不在本任务范围内，故此处仅如实记录该不对称。
   *
   * 标志（liked/favorited）：布尔无哨兵可用，改用 locallyToggled 脏标记。
   * - 该 id 未被本会话操作过 → 无条件接受服务端值（刷新后按钮能反映真实状态）；
   * - 已被操作过 → 跳过，保留用户操作结果（陈旧的列表负载不会回退它）。
   *
   * 由于 flags 与计数的保护规则不同，极端情况下可能出现「计数已被陈旧值回退、
   * 标志仍是本地值」的组合；两者都以本地为准时即不会自相矛盾。
   */
  function syncFromPost(post) {
    if (!post || !post.id) return
    const current = ensure(post.id)
    if (current.likeCount === 0 && post.likeCount != null) current.likeCount = post.likeCount
    if (current.favoriteCount === 0 && post.favoriteCount != null) {
      current.favoriteCount = post.favoriteCount
    }
    if (locallyToggled.has(post.id)) return
    if (post.liked != null) current.liked = post.liked
    if (post.favorited != null) current.favorited = post.favorited
  }

  function stateFor(postId) {
    return ensure(postId)
  }

  async function toggleLike(postId) {
    const current = ensure(postId)
    const snapshot = { liked: current.liked, likeCount: current.likeCount }
    locallyToggled.add(postId)
    try {
      const r = await postApi.like(postId)
      current.liked = r.active
      current.likeCount = r.count
      return r
    } catch (e) {
      current.liked = snapshot.liked
      current.likeCount = snapshot.likeCount
      throw e
    }
  }

  async function toggleFavorite(postId) {
    const current = ensure(postId)
    const snapshot = { favorited: current.favorited, favoriteCount: current.favoriteCount }
    locallyToggled.add(postId)
    try {
      const r = await postApi.favorite(postId)
      current.favorited = r.active
      current.favoriteCount = r.count
      return r
    } catch (e) {
      current.favorited = snapshot.favorited
      current.favoriteCount = snapshot.favoriteCount
      throw e
    }
  }

  return { syncFromPost, stateFor, toggleLike, toggleFavorite }
}
