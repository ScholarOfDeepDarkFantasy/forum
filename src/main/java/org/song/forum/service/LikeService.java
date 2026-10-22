package org.song.forum.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.song.forum.dto.post.LikeToggleResponse;
import org.song.forum.dto.post.PostSummaryResponse;
import org.song.forum.entity.Favorite;
import org.song.forum.entity.Like;
import org.song.forum.entity.NotificationType;
import org.song.forum.entity.Post;
import org.song.forum.entity.User;
import org.song.forum.exception.BusinessException;
import org.song.forum.repository.FavoriteRepository;
import org.song.forum.repository.LikeRepository;
import org.song.forum.repository.PostRepository;
import org.song.forum.repository.UserRepository;
import org.song.forum.util.PageResponse;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 点赞/收藏服务：切换式点赞收藏（已赞再点取消），联动 posts 冗余计数，
 * 点赞/收藏他人帖子时产生 LIKE/FAVORITE 通知（自己操作自己的帖子不通知）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LikeService {

    private final LikeRepository likeRepository;
    private final FavoriteRepository favoriteRepository;
    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    /** 切换点赞：未赞→赞(+1+通知)，已赞→取消(-1) */
    @Transactional
    public LikeToggleResponse toggleLike(Long postId, Long userId) {
        Post post = requireActivePost(postId);
        User actor = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("用户不存在"));

        return likeRepository.findByUserIdAndPostId(userId, postId)
                .map(existing -> {
                    likeRepository.delete(existing);
                    postRepository.decrementLikeCount(postId);
                    return new LikeToggleResponse(false, post.getLikeCount() - 1);
                })
                .orElseGet(() -> {
                    likeRepository.save(Like.builder().user(actor).post(post).build());
                    postRepository.incrementLikeCount(postId);
                    if (!post.getAuthor().getId().equals(userId)) {
                        notificationService.create(post.getAuthor(), actor, post, null,
                                NotificationType.LIKE,
                                actor.getUsername() + " 赞了你的帖子「" + post.getTitle() + "」");
                    }
                    return new LikeToggleResponse(true, post.getLikeCount() + 1);
                });
    }

    /** 切换收藏：未藏→藏(+1+通知)，已藏→取消(-1) */
    @Transactional
    public LikeToggleResponse toggleFavorite(Long postId, Long userId) {
        Post post = requireActivePost(postId);
        User actor = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("用户不存在"));

        return favoriteRepository.findByUserIdAndPostId(userId, postId)
                .map(existing -> {
                    favoriteRepository.delete(existing);
                    postRepository.decrementFavoriteCount(postId);
                    return new LikeToggleResponse(false, post.getFavoriteCount() - 1);
                })
                .orElseGet(() -> {
                    favoriteRepository.save(Favorite.builder().user(actor).post(post).build());
                    postRepository.incrementFavoriteCount(postId);
                    if (!post.getAuthor().getId().equals(userId)) {
                        notificationService.create(post.getAuthor(), actor, post, null,
                                NotificationType.FAVORITE,
                                actor.getUsername() + " 收藏了你的帖子「" + post.getTitle() + "」");
                    }
                    return new LikeToggleResponse(true, post.getFavoriteCount() + 1);
                });
    }

    /** 我的收藏列表（时间倒序） */
    @Transactional(readOnly = true)
    public PageResponse<PostSummaryResponse> myFavorites(Long userId, int page, int size) {
        Page<Favorite> favorites = favoriteRepository.findByUserIdOrderByCreateTimeDesc(userId,
                PageRequest.of(page, Math.min(size, 50), Sort.by(Sort.Direction.DESC, "createTime")));
        // favorited 定义上恒为 true；liked 仍需按真实点赞记录计算，用一条批量查询取回本页已赞帖子 ID
        List<Long> postIds = favorites.getContent().stream().map(f -> f.getPost().getId()).toList();
        if (postIds.isEmpty()) {
            return PageResponse.of(favorites.map(f -> toSummary(f.getPost(), false)));
        }
        // FavoriteRepository 的 EntityGraph 只覆盖 post/post.board/post.author，不含 post.tags，
        // 直接用会在渲染标签时每行触发一次 select ... from post_tags where post_id=?。
        // 按 id 批量补齐一次，顺带把 to-one 关联也一次性抓回来。
        Map<Long, Post> byId = postRepository.findWithAssociations(postIds).stream()
                .collect(Collectors.toMap(Post::getId, p -> p));
        Set<Long> likedIds = new HashSet<>(likeRepository.findLikedPostIds(userId, postIds));
        return PageResponse.of(favorites.map(f -> {
            Post hydrated = byId.getOrDefault(f.getPost().getId(), f.getPost());
            return toSummary(hydrated, likedIds.contains(hydrated.getId()));
        }));
    }

    // ---------- 内部方法 ----------

    private Post requireActivePost(Long postId) {
        Post post = postRepository.findWithDetailsById(postId)
                .orElseThrow(() -> new BusinessException("帖子不存在"));
        if (post.getDeleted()) {
            throw new BusinessException("帖子已被删除");
        }
        return post;
    }

    private PostSummaryResponse toSummary(Post post, boolean liked) {
        return PostSummaryResponse.builder()
                .id(post.getId())
                .boardId(post.getBoard().getId())
                .boardName(post.getBoard().getName())
                .authorId(post.getAuthor().getId())
                .authorUsername(post.getAuthor().getUsername())
                .authorNickname(post.getAuthor().getNickname())
                .title(post.getTitle())
                .likeCount(post.getLikeCount())
                .commentCount(post.getCommentCount())
                .viewCount(post.getViewCount())
                .isPinned(post.getIsPinned())
                .isExquisite(post.getIsExquisite())
                .tags(post.getTags().stream().map(t -> t.getName()).toList())
                .createTime(post.getCreateTime())
                .liked(liked)
                .favorited(true)   // 我的收藏列表里的条目必然已收藏
                .build();
    }
}
