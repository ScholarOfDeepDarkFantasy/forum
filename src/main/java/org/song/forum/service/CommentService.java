package org.song.forum.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.song.forum.dto.comment.CommentRequest;
import org.song.forum.dto.comment.CommentResponse;
import org.song.forum.entity.Comment;
import org.song.forum.entity.Notification;
import org.song.forum.entity.NotificationType;
import org.song.forum.entity.Post;
import org.song.forum.entity.User;
import org.song.forum.exception.BusinessException;
import org.song.forum.repository.CommentRepository;
import org.song.forum.repository.NotificationRepository;
import org.song.forum.repository.PostRepository;
import org.song.forum.repository.UserRepository;
import org.song.forum.util.PageResponse;

/**
 * 评论服务：发表评论/楼中楼回复、树形读取、软删除占位。
 * 维护 posts.comment_count 冗余计数，回复他人评论时产生 REPLY 通知。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentService {

    private static final String DELETED_PLACEHOLDER = "该评论已删除";

    private final CommentRepository commentRepository;
    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;

    /** 发表评论（parentId 非空时为楼中楼回复） */
    @Transactional
    public CommentResponse create(Long postId, Long authorId, CommentRequest req) {
        Post post = postRepository.findWithDetailsById(postId)
                .orElseThrow(() -> new BusinessException("帖子不存在"));
        if (post.getDeleted()) {
            throw new BusinessException("帖子已被删除，不能评论");
        }
        User author = userRepository.findById(authorId)
                .orElseThrow(() -> new BusinessException("用户不存在"));

        Comment parent = null;
        if (req.getParentId() != null) {
            parent = commentRepository.findById(req.getParentId())
                    .orElseThrow(() -> new BusinessException("父评论不存在"));
            if (!parent.getPost().getId().equals(postId)) {
                throw new BusinessException("父评论不属于该帖子");
            }
            if (parent.getDeleted()) {
                throw new BusinessException("父评论已删除，不能回复");
            }
            // 楼中楼只允许一层：回复楼中楼时挂到其顶级父评论下（扁平化，避免无限嵌套）
            if (parent.getParent() != null) {
                parent = parent.getParent();
            }
        }

        Comment comment = Comment.builder()
                .post(post)
                .author(author)
                .parent(parent)
                .content(req.getContent().trim())
                .build();
        comment = commentRepository.save(comment);

        // 冗余计数 +1
        postRepository.incrementCommentCount(postId);

        // 回复他人评论 → REPLY 通知（自己回复自己不通知）
        if (parent != null && !parent.getAuthor().getId().equals(authorId)) {
            notificationRepository.save(Notification.builder()
                    .recipient(parent.getAuthor())
                    .sender(author)
                    .post(post)
                    .type(NotificationType.REPLY)
                    .content(author.getUsername() + " 回复了你的评论")
                    .build());
        }
        log.info("用户 {} 评论帖子 {}: {}", author.getUsername(), postId, comment.getId());
        return toResponse(comment);
    }

    /** 帖子评论树：顶级评论分页 + 子回复平铺 */
    @Transactional(readOnly = true)
    public PageResponse<CommentResponse> listByPost(Long postId, int page, int size) {
        postRepository.findById(postId)
                .orElseThrow(() -> new BusinessException("帖子不存在"));
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "createTime"));
        Page<Comment> roots = commentRepository
                .findByPostIdAndParentIsNullAndDeletedFalse(postId, pageable);
        Page<CommentResponse> mapped = roots.map(root -> {
            CommentResponse resp = toResponse(root);
            commentRepository.findByParentIdAndDeletedFalseOrderByIdAsc(root.getId())
                    .forEach(child -> resp.getReplies().add(toResponse(child)));
            return resp;
        });
        return PageResponse.of(mapped);
    }

    /** 软删除评论：内容替换为占位，保持楼中楼结构 */
    @Transactional
    public void delete(Long commentId, Long userId) {
        Comment comment = commentRepository.findWithDetailsById(commentId)
                .orElseThrow(() -> new BusinessException("评论不存在"));
        if (!comment.getAuthor().getId().equals(userId)) {
            throw new BusinessException("只能删除自己的评论");
        }
        if (comment.getDeleted()) {
            throw new BusinessException("评论已删除");
        }
        comment.setDeleted(true);
        comment.setContent(DELETED_PLACEHOLDER);
        commentRepository.save(comment);
        postRepository.decrementCommentCount(comment.getPost().getId());
        log.info("评论 {} 被用户 {} 软删除", commentId, userId);
    }

    // ---------- 内部方法 ----------

    private CommentResponse toResponse(Comment c) {
        boolean deleted = Boolean.TRUE.equals(c.getDeleted());
        return CommentResponse.builder()
                .id(c.getId())
                .postId(c.getPost().getId())
                .parentId(c.getParent() == null ? null : c.getParent().getId())
                .authorId(deleted ? null : c.getAuthor().getId())
                .authorUsername(deleted ? null : c.getAuthor().getUsername())
                .authorNickname(deleted ? null : c.getAuthor().getNickname())
                .authorAvatar(deleted ? null : c.getAuthor().getAvatarUrl())
                .content(deleted ? DELETED_PLACEHOLDER : c.getContent())
                .deleted(deleted)
                .createTime(c.getCreateTime())
                .build();
    }
}
