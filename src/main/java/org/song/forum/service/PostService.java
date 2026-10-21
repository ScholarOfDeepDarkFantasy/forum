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
import org.song.forum.repository.FavoriteRepository;
import org.song.forum.repository.LikeRepository;
import org.song.forum.repository.PostRepository;
import org.song.forum.repository.TagRepository;
import org.song.forum.repository.UserRepository;
import org.song.forum.security.SecurityUtils;
import org.song.forum.util.PageResponse;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 帖子服务：发帖/编辑/删除（作者本人）、分页搜索、详情+浏览量
 * <p>注意：{@code toDetail(Post)} / {@code mapSummaries} 通过 {@link SecurityUtils} 从
 * {@code SecurityContextHolder} 读取当前登录用户，属于请求线程作用域（request-thread scoped）。
 * 因此它们不得在异步或定时任务（{@code @Async} / {@code @Scheduled} / {@code CompletableFuture}
 * 等脱离请求线程的代码）中调用，否则会静默按游客处理。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostService {

    private final PostRepository postRepository;
    private final BoardRepository boardRepository;
    private final UserRepository userRepository;
    private final TagRepository tagRepository;
    private final LikeRepository likeRepository;
    private final FavoriteRepository favoriteRepository;

    /** 发帖 */
    @Transactional
    public PostDetailResponse create(Long authorId, PostRequest req) {
        User author = userRepository.findById(authorId)
                .orElseThrow(() -> new BusinessException("用户不存在"));
        requireNotMuted(author);
        Board board = boardRepository.findById(req.getBoardId())
                .orElseThrow(() -> new BusinessException("板块不存在"));

        Post post = Post.builder()
                .board(board)
                .author(author)
                .title(req.getTitle())
                .content(req.getContent())
                .build();
        post.setTags(resolveTags(req.getTags()));
        post = postRepository.save(post);
        log.info("用户 {} 发帖: {}", author.getUsername(), post.getTitle());
        // 与其它写接口保持一致：按作者推导互动状态（新帖查无记录，结果同样是 false）
        return toDetail(post, authorId);
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
        return toDetail(postRepository.save(post), userId);
    }

    /** 软删除帖子：作者本人可删；管理员/版主可删任何帖子 */
    @Transactional
    public void delete(Long postId, Long userId) {
        Post post = postRepository.findWithDetailsById(postId)
                .orElseThrow(() -> new BusinessException("帖子不存在"));
        if (!post.getAuthor().getId().equals(userId) && !isModerator(userId)) {
            throw new BusinessException("只能操作自己的帖子");
        }
        post.setDeleted(true);
        postRepository.save(post);
        log.info("帖子 {} 被用户 {} 软删除", postId, userId);
    }

    /** 置顶/取消置顶（管理员/版主） */
    @Transactional
    public PostDetailResponse setPinned(Long postId, boolean pinned) {
        Post post = postRepository.findWithDetailsById(postId)
                .orElseThrow(() -> new BusinessException("帖子不存在"));
        post.setIsPinned(pinned);
        return toDetail(postRepository.save(post));
    }

    /** 加精/取消加精（管理员/版主） */
    @Transactional
    public PostDetailResponse setExquisite(Long postId, boolean exquisite) {
        Post post = postRepository.findWithDetailsById(postId)
                .orElseThrow(() -> new BusinessException("帖子不存在"));
        post.setIsExquisite(exquisite);
        return toDetail(postRepository.save(post));
    }

    /** 帖子分页列表：boardId 可选，keyword 可选；置顶优先，其余默认按创建时间倒序 */
    @Transactional(readOnly = true)
    public PageResponse<PostSummaryResponse> list(Long boardId, String keyword, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, pinnedFirstSort());
        // 注意：keyword 不能传 null——pgJDBC 会把无类型 null 绑定为 bytea，
        // PostgreSQL 报 "function lower(bytea) does not exist"（SQLState 42883）。
        // 空串有确定类型（varchar），JPQL 端用 = '' 表示"无关键字"。
        //
        // 分两步查：先在 SQL 层正确分页拿到骨架，再按 id 批量抓 tags 集合。
        // 若直接在 tags 集合上 EntityGraph + Pageable，Hibernate 会打印 HHH90003004
        // 并把全部匹配行读进内存再截取，数据量一大就是性能事故。
        String kw = blankToEmpty(keyword);
        Page<Post> skeletons = postRepository.searchIds(boardId, kw, pageable);
        return PageResponse.of(mapSummaries(hydrate(skeletons)));
    }

    /** 我的帖子 */
    @Transactional(readOnly = true)
    public PageResponse<PostSummaryResponse> myPosts(Long authorId, int page, int size) {
        Page<Post> skeletons = postRepository.findIdsByAuthorIdAndDeletedFalse(authorId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createTime")));
        return PageResponse.of(mapSummaries(hydrate(skeletons)));
    }

    /** 标签帖子列表：复用与首页相同的两步查询与互动状态计算；标签不存在时返回空页 */
    @Transactional(readOnly = true)
    public PageResponse<PostSummaryResponse> listByTag(String tagName, int page, int size) {
        String normalized = tagName == null ? "" : tagName.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || tagRepository.findByName(normalized).isEmpty()) {
            return new PageResponse<>(List.of(), page, size, 0, 0, true);
        }
        Pageable pageable = PageRequest.of(page, size, pinnedFirstSort());
        Page<Post> skeletons = postRepository.searchByTagName(normalized, pageable);
        return PageResponse.of(mapSummaries(hydrate(skeletons)));
    }

    /**
     * 全文搜索：标题命中优先，同档按创建时间倒序、时间相同再按 id 倒序。
     * <p>关键词去首尾空白后必须是 1~50 个 code point；page 负数按 0，size 夹到 1~50。
     * 这里再夹取一次 size 是防御性的：controller 也夹了一次，service 作为公开方法
     * 不依赖调用方已经处理过。</p>
     */
    @Transactional(readOnly = true)
    public PageResponse<PostSummaryResponse> search(String rawKeyword, int page, int size) {
        String keyword = normalizeSearchKeyword(rawKeyword);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 50);
        // 小写 + 转义都在 Java 侧做，JPQL 只拼 %...%；大小写不敏感由 SQL 的 LOWER 保证。
        String pattern = escapeLike(keyword.toLowerCase(Locale.ROOT));
        // pageable 必须不带 Sort：排序写在 JPQL 的 ORDER BY 里，再带 Sort 会被拼成第二个 ORDER BY。
        Page<Post> skeletons = postRepository.searchByKeyword(pattern, PageRequest.of(safePage, safeSize));
        Page<Post> hydrated = hydrate(skeletons);
        Page<PostSummaryResponse> summaries = mapSummaries(hydrated);
        // 搜索页要显示正文命中处的一行上下文，而列表 DTO 不带正文：
        // 只为当前页附一段窗口。snippetOf 返回空串而非 null（Collectors.toMap 不接受 null 值）。
        Map<Long, String> snippets = hydrated.getContent().stream()
                .collect(Collectors.toMap(Post::getId, p -> snippetOf(p.getContent(), keyword)));
        summaries.forEach(s -> s.setSnippet(snippets.get(s.getId())));
        return PageResponse.of(summaries);
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

    /** 作者本人校验（编辑帖子用；删除已扩展为版主以上放行） */
    private void requireOwner(Post post, Long userId) {
        if (!post.getAuthor().getId().equals(userId)) {
            throw new BusinessException("只能操作自己的帖子");
        }
    }

    /** 是否管理员/版主（用于删除帖子越权放行） */
    private boolean isModerator(Long userId) {
        return userRepository.findById(userId)
                .map(u -> u.getRoles().stream()
                        .anyMatch(r -> "ADMIN".equals(r.getName()) || "MODERATOR".equals(r.getName())))
                .orElse(false);
    }

    /** 禁言拦截：BANNED/MUTED 用户不能发帖（管理员/版主不受限） */
    private void requireNotMuted(User user) {
        boolean privileged = user.getRoles().stream()
                .anyMatch(r -> "ADMIN".equals(r.getName()) || "MODERATOR".equals(r.getName()));
        if (privileged) {
            return;
        }
        if (user.getStatus() == org.song.forum.entity.UserStatus.BANNED) {
            throw new BusinessException("账号已被封禁，不能发帖");
        }
        if (user.getStatus() == org.song.forum.entity.UserStatus.MUTED) {
            if (user.getMutedUntil() != null && user.getMutedUntil().isAfter(java.time.LocalDateTime.now())) {
                throw new BusinessException("已被禁言至 " + user.getMutedUntil() + "，暂不能发帖");
            }
            // 禁言已到期，自动恢复
            user.setStatus(org.song.forum.entity.UserStatus.ACTIVE);
            user.setMutedUntil(null);
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

    /** 空白转空串（非 null）：PostgreSQL 下无类型 null 绑定会变 bytea */
    private String blankToEmpty(String s) {
        return (s == null || s.isBlank()) ? "" : s.trim();
    }

    /**
     * 搜索关键词归一化：去首尾空白、限长 1~50 个 code point，保留原始大小写。
     * 用 codePointCount 而不是 length：emoji/生僻字的 length 是 2，按 length 判长会误伤。
     */
    private static String normalizeSearchKeyword(String rawKeyword) {
        String keyword = rawKeyword == null ? "" : rawKeyword.trim();
        if (keyword.isEmpty()) {
            throw new BusinessException("搜索关键词不能为空");
        }
        if (keyword.codePointCount(0, keyword.length()) > 50) {
            throw new BusinessException("搜索关键词不能超过 50 个字符");
        }
        return keyword;
    }

    /**
     * 转义 LIKE 通配符：'!' 是 JPQL 里 ESCAPE 指定的转义符，所以它自己也要先转义。
     * 不转义时 '%'（如「100%完成」）会被当成任意串，搜「100%」会误命中「1000完成」。
     */
    private static String escapeLike(String keyword) {
        StringBuilder sb = new StringBuilder(keyword.length() + 8);
        for (int i = 0; i < keyword.length(); i++) {
            char c = keyword.charAt(i);
            if (c == '!' || c == '%' || c == '_') {
                sb.append('!');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 搜索片段窗口半径：以正文命中位置为中心，左右各保留的字符数 */
    private static final int SNIPPET_RADIUS = 80;

    /**
     * 取正文命中处的一段纯文本窗口。
     * <p>这里只截窗口：不清洗 Markdown、不加省略号——清洗、省略号与高亮统一由前端
     * {@code utils/search.js} 的 {@code buildSnippet} 完成，避免同一套规则在两种语言里各写一遍。
     * 正文不含关键词（标题命中）时返回空串。</p>
     * <p>窗口端点会吸附到 code point 边界：UTF-16 下标可能切在代理对中间，
     * 留下落单的 surrogate（序列化时变替换字符）。吸附后最多多保留一个字符。</p>
     */
    private static String snippetOf(String content, String keyword) {
        if (content == null || content.isEmpty() || keyword.isEmpty()) {
            return "";
        }
        int index = content.toLowerCase(Locale.ROOT).indexOf(keyword.toLowerCase(Locale.ROOT));
        if (index < 0) {
            return "";
        }
        int start = Math.max(0, index - SNIPPET_RADIUS);
        int end = Math.min(content.length(), index + keyword.length() + SNIPPET_RADIUS);
        // start 落在 low surrogate 上说明前一个 code point 被切开了，回退一格包含完整的代理对
        if (start > 0 && Character.isLowSurrogate(content.charAt(start))) {
            start--;
        }
        // end 的前一个字符是 high surrogate 说明最后一个 code point 被切开了，前进一格补齐
        if (end < content.length() && Character.isHighSurrogate(content.charAt(end - 1))) {
            end++;
        }
        return content.substring(start, end);
    }

    private PostSummaryResponse toSummary(Post p, boolean liked, boolean favorited) {
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
                .liked(liked)
                .favorited(favorited)
                .build();
    }

    private PostDetailResponse toDetail(Post p) {
        return toDetail(p, null);
    }

    /**
     * 组装详情：互动状态按当前登录用户计算（游客为 false）。
     *
     * @param userId 已知的当前用户 ID；传 null 时从 SecurityContext 读取，未登录则视为游客。
     *               显式传入可避免在已有 userId 参数的写接口里重复读取上下文。
     */
    private PostDetailResponse toDetail(Post p, Long userId) {
        Long uid = userId != null ? userId : SecurityUtils.currentUserIdOrNull();
        boolean liked = uid != null && likeRepository.findByUserIdAndPostId(uid, p.getId()).isPresent();
        boolean favorited = uid != null && favoriteRepository.findByUserIdAndPostId(uid, p.getId()).isPresent();
        return toDetail(p, liked, favorited);
    }

    private PostDetailResponse toDetail(Post p, boolean liked, boolean favorited) {
        PostSummaryResponse s = toSummary(p, liked, favorited);
        PostDetailResponse d = PostDetailResponse.of(s, p.getContent());
        d.setFavoriteCount(p.getFavoriteCount());
        d.setUpdateTime(p.getUpdateTime());
        return d;
    }

    /**
     * 两步查询的第二步：按 id 批量抓取 board/author/tags，并按第一步的顺序重建 {@link Page}。
     * IN 查询不保证顺序，直接返回会让列表顺序变成数据库返回顺序，分页排序就失效了。
     */
    private Page<Post> hydrate(Page<Post> skeletons) {
        List<Long> ids = skeletons.getContent().stream().map(Post::getId).toList();
        if (ids.isEmpty()) {
            return skeletons;
        }
        Map<Long, Post> byId = postRepository.findWithAssociations(ids).stream()
                .collect(Collectors.toMap(Post::getId, p -> p));
        return skeletons.map(p -> byId.getOrDefault(p.getId(), p));
    }

    /**
     * 列表映射：先用一条批量查询取回"当前用户在本页帖子中已赞/已藏的帖子 ID"，
     * 再在内存里判断，避免逐行查询导致 N+1（游客或空页直接跳过查询）。
     */
    private Page<PostSummaryResponse> mapSummaries(Page<Post> posts) {
        List<Long> postIds = posts.getContent().stream().map(Post::getId).toList();
        Long uid = SecurityUtils.currentUserIdOrNull();
        Set<Long> likedIds = new HashSet<>();
        Set<Long> favoritedIds = new HashSet<>();
        if (uid != null && !postIds.isEmpty()) {
            likedIds.addAll(likeRepository.findLikedPostIds(uid, postIds));
            favoritedIds.addAll(favoriteRepository.findFavoritedPostIds(uid, postIds));
        }
        return posts.map(p -> toSummary(p, likedIds.contains(p.getId()), favoritedIds.contains(p.getId())));
    }
}
