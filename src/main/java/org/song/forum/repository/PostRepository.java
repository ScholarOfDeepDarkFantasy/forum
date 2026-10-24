package org.song.forum.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.song.forum.entity.Post;

import java.util.Collection;
import java.util.List;

public interface PostRepository extends JpaRepository<Post, Long> {

    /**
     * 分页第一步：只取帖子骨架，不 join {@code tags} 集合。
     * <p>to-one 关联（board/author）可以安全 join——它们不会让结果行数膨胀，
     * 因此能在 SQL 层正确分页。集合关联若与分页同用，Hibernate 会打印
     * {@code HHH90003004} 并把全部匹配行读进内存再截取。</p>
     * <p>boardId 为 null 查全部；keyword 传空串表示无关键字（不能传 null，
     * 见 {@code PostService.list} 里关于 pgJDBC 的注释）。</p>
     * <p>keyword 已由 service 用 '!' 转义，因此这里必须带 ESCAPE '!'，否则 %/_ 仍被当通配符。</p>
     */
    @Query("""
            SELECT p FROM Post p
            WHERE p.deleted = false
              AND (:boardId IS NULL OR p.board.id = :boardId)
              AND (:keyword = '' OR LOWER(p.title) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '!'
                                 OR LOWER(p.content) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '!')
            """)
    @EntityGraph(attributePaths = {"board", "author"})
    Page<Post> searchIds(@Param("boardId") Long boardId,
                         @Param("keyword") String keyword,
                         Pageable pageable);

    /**
     * 全文搜索分页第一步：只取骨架，不 join tags 集合（原因同 {@link #searchIds}）。
     * pattern 由 service 层做过 LIKE 通配符转义（! % _ → !x），SQL 用 ESCAPE '!'。
     * 排序：标题命中优先（CASE 0/1），同档按 createTime 倒序、id 倒序；置顶不加权。
     * 追加 id 是兜底：createTime 相同（批量导入/同微秒）时，仅按时间排序在 PostgreSQL
     * 下顺序不稳定，翻页会出现重复或漏行。
     */
    @Query("""
            SELECT p FROM Post p
            WHERE p.deleted = false
              AND (LOWER(p.title) LIKE CONCAT('%', :pattern, '%') ESCAPE '!'
                   OR LOWER(p.content) LIKE CONCAT('%', :pattern, '%') ESCAPE '!')
            ORDER BY CASE WHEN LOWER(p.title) LIKE CONCAT('%', :pattern, '%') ESCAPE '!' THEN 0 ELSE 1 END,
                     p.createTime DESC, p.id DESC
            """)
    @EntityGraph(attributePaths = {"board", "author"})
    Page<Post> searchByKeyword(@Param("pattern") String pattern, Pageable pageable);

    /**
     * 分页第二步：按 id 批量抓取帖子及全部所需关联（含 tags 集合）。
     * IN 查询不保证顺序，调用方必须用第一步的结果重建顺序。
     */
    @Query("SELECT p FROM Post p WHERE p.id IN :ids")
    @EntityGraph(attributePaths = {"board", "author", "tags"})
    List<Post> findWithAssociations(@Param("ids") Collection<Long> ids);

    /** 某标签下的帖子分页第一步：只取骨架，不 join tags 集合（原因同 searchIds） */
    @Query("SELECT p FROM Post p JOIN p.tags t WHERE p.deleted = false AND t.name = :name")
    @EntityGraph(attributePaths = {"board", "author"})
    Page<Post> searchByTagName(@Param("name") String name, Pageable pageable);

    /** "我的帖子"分页第一步：同 {@link #searchIds}，只取骨架不 join tags。 */
    @EntityGraph(attributePaths = {"board", "author"})
    Page<Post> findIdsByAuthorIdAndDeletedFalse(Long authorId, Pageable pageable);

    /** 详情抓取，避免 N+1 */
    @EntityGraph(attributePaths = {"board", "author", "tags"})
    java.util.Optional<Post> findWithDetailsById(Long id);

    /** 浏览量 +1（独立 UPDATE，不污染乐观并发场景） */
    @Modifying
    @Query("UPDATE Post p SET p.viewCount = p.viewCount + 1 WHERE p.id = :id")
    void incrementViewCount(@Param("id") Long id);

    /** 评论数 +1（发评论时由 CommentService 调用） */
    @Modifying
    @Query("UPDATE Post p SET p.commentCount = p.commentCount + 1 WHERE p.id = :id")
    void incrementCommentCount(@Param("id") Long id);

    /** 评论数 -1（软删除评论时由 CommentService 调用） */
    @Modifying
    @Query("UPDATE Post p SET p.commentCount = p.commentCount - 1 WHERE p.id = :id")
    void decrementCommentCount(@Param("id") Long id);

    /** 点赞数 +1（点赞时由 LikeService 调用） */
    @Modifying
    @Query("UPDATE Post p SET p.likeCount = p.likeCount + 1 WHERE p.id = :id")
    void incrementLikeCount(@Param("id") Long id);

    /** 点赞数 -1（取消点赞时由 LikeService 调用） */
    @Modifying
    @Query("UPDATE Post p SET p.likeCount = p.likeCount - 1 WHERE p.id = :id")
    void decrementLikeCount(@Param("id") Long id);

    /** 收藏数 +1（收藏时由 LikeService 调用） */
    @Modifying
    @Query("UPDATE Post p SET p.favoriteCount = p.favoriteCount + 1 WHERE p.id = :id")
    void incrementFavoriteCount(@Param("id") Long id);

    /** 收藏数 -1（取消收藏时由 LikeService 调用） */
    @Modifying
    @Query("UPDATE Post p SET p.favoriteCount = p.favoriteCount - 1 WHERE p.id = :id")
    void decrementFavoriteCount(@Param("id") Long id);

    /** 板块下未删除帖子数（板块列表展示用） */
    @Query("SELECT COUNT(p) FROM Post p WHERE p.board.id = :boardId AND p.deleted = false")
    long countByBoardIdAndDeletedFalse(@Param("boardId") Long boardId);
}
