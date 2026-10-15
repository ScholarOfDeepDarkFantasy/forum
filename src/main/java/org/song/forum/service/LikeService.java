package org.song.forum.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.song.forum.dto.post.LikeToggleResponse;
import org.song.forum.dto.post.PostSummaryResponse;
import org.song.forum.entity.Favorite;
import org.song.forum.entity.Like;
import org.song.forum.entity.Notification;
import org.song.forum.entity.NotificationType;
import org.song.forum.entity.Post;
import org.song.forum.entity.User;
import org.song.forum.exception.BusinessException;
import org.song.forum.repository.FavoriteRepository;
import org.song.forum.repository.LikeRepository;
import org.song.forum.repository.NotificationRepository;
import org.song.forum.repository.PostRepository;
import org.song.forum.repository.UserRepository;
import org.song.forum.util.PageResponse;

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
    private final NotificationRepository notificationRepository;

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
                        notificationRepository.save(Notification.builder()
                                .recipient(post.getAuthor())
                                .sender(actor)
                                .post(post)
                                .type(NotificationType.LIKE)
                                .content(actor.getUsername() + " 赞了你的帖子「" + post.getTitle() + "」")
                                .build());
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
                        notificationRepository.save(Notification.builder()
                                .recipient(post.getAuthor())
                                .sender(actor)
                                .post(post)
                                .type(NotificationType.FAVORITE)
                                .content(actor.getUsername() + " 收藏了你的帖子「" + post.getTitle() + "」")
                                .build());
                    }
                    return new LikeToggleResponse(true, post.getFavoriteCount() + 1);
                });
    }

    /** 我的收藏列表（时间倒序） */
    @Transactional(readOnly = true)
    public PageResponse<PostSummaryResponse> myFavorites(Long userId, int page, int size) {
        return PageResponse.of(favoriteRepository
                .findByUserIdOrderByCreateTimeDesc(userId, PageRequest.of(page, Math.min(size, 50),
                        Sort.by(Sort.Direction.DESC, "createTime")))
                .map(f -> toSummary(f.getPost())));
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

    private PostSummaryResponse toSummary(Post post) {
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
                .build();
    }
}
