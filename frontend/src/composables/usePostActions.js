import { reactive } from 'vue'
import { postApi } from '@/api'

// 模块级共享：首页列表与详情页读写同一份状态，保证点赞/收藏显示一致
const stateMap = reactive({})

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

  /** 用后端返回的帖子数据初始化（不覆盖用户本次会话内的操作结果） */
  function syncFromPost(post) {
    if (!post || !post.id) return
    const current = ensure(post.id)
    if (current.likeCount === 0 && post.likeCount != null) current.likeCount = post.likeCount
    if (current.favoriteCount === 0 && post.favoriteCount != null) {
      current.favoriteCount = post.favoriteCount
    }
  }

  function stateFor(postId) {
    return ensure(postId)
  }

  async function toggleLike(postId) {
    const current = ensure(postId)
    const snapshot = { liked: current.liked, likeCount: current.likeCount }
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
