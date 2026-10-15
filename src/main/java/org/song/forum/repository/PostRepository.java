package org.song.forum.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.song.forum.entity.Post;

public interface PostRepository extends JpaRepository<Post, Long> {

    /**
     * 帖子分页：boardId 为 null 查全部；只取未删除；置顶优先（由 Service 排序或排序参数控制）。
     * EntityGraph 抓取 board/author，避免 N+1。
     */
    @EntityGraph(attributePaths = {"board", "author", "tags"})
    @Query("""
            SELECT p FROM Post p
            WHERE p.deleted = false
              AND (:boardId IS NULL OR p.board.id = :boardId)
              AND (:keyword = '' OR LOWER(p.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                                 OR LOWER(p.content) LIKE LOWER(CONCAT('%', :keyword, '%')))
            """)
    Page<Post> search(@Param("boardId") Long boardId,
                      @Param("keyword") String keyword,
                      Pageable pageable);

    /** 我的帖子 */
    @EntityGraph(attributePaths = {"board", "author", "tags"})
    Page<Post> findByAuthorIdAndDeletedFalse(Long authorId, Pageable pageable);

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
