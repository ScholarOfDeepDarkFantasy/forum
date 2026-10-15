package org.song.forum.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.song.forum.dto.post.PostDetailResponse;
import org.song.forum.dto.post.PostRequest;
import org.song.forum.dto.post.PostSummaryResponse;
import org.song.forum.entity.Board;
import org.song.forum.entity.Post;
import org.song.forum.entity.Tag;
import org.song.forum.entity.User;
import org.song.forum.exception.BusinessException;
import org.song.forum.repository.BoardRepository;
import org.song.forum.repository.PostRepository;
import org.song.forum.repository.TagRepository;
import org.song.forum.repository.UserRepository;
import org.song.forum.util.PageResponse;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 帖子服务：发帖/编辑/删除（作者本人）、分页搜索、详情+浏览量
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostService {

    private final PostRepository postRepository;
    private final BoardRepository boardRepository;
    private final UserRepository userRepository;
    private final TagRepository tagRepository;

    /** 发帖 */
    @Transactional
    public PostDetailResponse create(Long authorId, PostRequest req) {
        Board board = boardRepository.findById(req.getBoardId())
                .orElseThrow(() -> new BusinessException("板块不存在"));
        User author = userRepository.findById(authorId)
                .orElseThrow(() -> new BusinessException("用户不存在"));

        Post post = Post.builder()
                .board(board)
                .author(author)
                .title(req.getTitle())
                .content(req.getContent())
                .build();
        post.setTags(resolveTags(req.getTags()));
        post = postRepository.save(post);
        log.info("用户 {} 发帖: {}", author.getUsername(), post.getTitle());
        return toDetail(post);
    }

    /** 编辑帖子（仅作者本人） */
    @Transactional
    public PostDetailResponse update(Long postId, Long userId, PostRequest req) {
        Post post = postRepository.findWithDetailsById(postId)
                .orElseThrow(() -> new BusinessException("帖子不存在"));
        requireOwner(post, userId);

        Board board = boardRepository.findById(req.getBoardId())
                .orElseThrow(() -> new BusinessException("板块不存在"));
        post.setBoard(board);
        post.setTitle(req.getTitle());
        post.setContent(req.getContent());
        post.getTags().clear();
        post.getTags().addAll(resolveTags(req.getTags()));
        return toDetail(postRepository.save(post));
    }

    /** 软删除帖子（作者本人） */
    @Transactional
    public void delete(Long postId, Long userId) {
        Post post = postRepository.findWithDetailsById(postId)
                .orElseThrow(() -> new BusinessException("帖子不存在"));
        requireOwner(post, userId);
        post.setDeleted(true);
        postRepository.save(post);
        log.info("帖子 {} 被用户 {} 软删除", postId, userId);
    }

    /** 帖子分页列表：boardId 可选，keyword 可选；置顶优先，其余默认按创建时间倒序 */
    @Transactional(readOnly = true)
    public PageResponse<PostSummaryResponse> list(Long boardId, String keyword, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, pinnedFirstSort());
        return PageResponse.of(postRepository.search(boardId, blankToNull(keyword), pageable)
                .map(this::toSummary));
    }

    /** 我的帖子 */
    @Transactional(readOnly = true)
    public PageResponse<PostSummaryResponse> myPosts(Long authorId, int page, int size) {
        return PageResponse.of(postRepository.findByAuthorIdAndDeletedFalse(authorId,
                        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createTime")))
                .map(this::toSummary));
    }

    /** 帖子详情：浏览量原子 +1 后返回 */
    @Transactional
    public PostDetailResponse getDetail(Long postId) {
        Post post = postRepository.findWithDetailsById(postId)
                .orElseThrow(() -> new BusinessException("帖子不存在"));
        if (post.getDeleted()) {
            throw new BusinessException("帖子已被删除");
        }
        postRepository.incrementViewCount(post.getId());
        PostDetailResponse detail = toDetail(post);
        detail.setViewCount(post.getViewCount() + 1); // 展示自增后的值
        return detail;
    }

    // ---------- 内部方法 ----------

    /** 作者本人校验（管理员留待第 5 阶段扩展） */
    private void requireOwner(Post post, Long userId) {
        if (!post.getAuthor().getId().equals(userId)) {
            throw new BusinessException("只能操作自己的帖子");
        }
    }

    /** 标签解析：按名查存，不存在则创建；统一小写防止 "Java"/"java" 重复 */
    private Set<Tag> resolveTags(List<String> tagNames) {
        Set<Tag> result = new HashSet<>();
        if (tagNames == null) return result;
        for (String name : tagNames) {
            if (name == null || name.isBlank()) continue;
            String normalized = name.trim().toLowerCase(Locale.ROOT);
            Tag tag = tagRepository.findByName(normalized)
                    .orElseGet(() -> tagRepository.save(Tag.builder().name(normalized).build()));
            result.add(tag);
        }
        return result;
    }

    /** 列表排序：置顶帖恒在最前，组内按创建时间倒序 */
    private Sort pinnedFirstSort() {
        return Sort.by(Sort.Order.desc("isPinned"), Sort.Order.desc("createTime"));
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private PostSummaryResponse toSummary(Post p) {
        return PostSummaryResponse.builder()
                .id(p.getId())
                .boardId(p.getBoard().getId())
                .boardName(p.getBoard().getName())
                .authorId(p.getAuthor().getId())
                .authorUsername(p.getAuthor().getUsername())
                .authorNickname(p.getAuthor().getNickname())
                .title(p.getTitle())
                .likeCount(p.getLikeCount())
                .commentCount(p.getCommentCount())
                .viewCount(p.getViewCount())
                .isPinned(p.getIsPinned())
                .isExquisite(p.getIsExquisite())
                .tags(p.getTags().stream().map(Tag::getName).toList())
                .createTime(p.getCreateTime())
                .build();
    }

    private PostDetailResponse toDetail(Post p) {
        PostSummaryResponse s = toSummary(p);
        PostDetailResponse d = PostDetailResponse.of(s, p.getContent());
        d.setFavoriteCount(p.getFavoriteCount());
        d.setUpdateTime(p.getUpdateTime());
        return d;
    }
}
