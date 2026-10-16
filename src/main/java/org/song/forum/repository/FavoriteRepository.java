package org.song.forum.repository;

import org.song.forum.entity.Favorite;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FavoriteRepository extends JpaRepository<Favorite, Long> {

    Optional<Favorite> findByUserIdAndPostId(Long userId, Long postId);

    /** 我的收藏列表 */
    @EntityGraph(attributePaths = {"post", "post.board", "post.author"})
    Page<Favorite> findByUserIdOrderByCreateTimeDesc(Long userId, Pageable pageable);

    /**
     * 批量取"当前用户在一批帖子中已收藏的帖子 ID"，供列表接口一次性计算 favorited 状态，
     * 避免逐行查询造成 N+1（只查 post_id 列，不加载实体）。
     */
    @Query("SELECT f.post.id FROM Favorite f WHERE f.user.id = :userId AND f.post.id IN :postIds")
    List<Long> findFavoritedPostIds(@Param("userId") Long userId, @Param("postIds") Collection<Long> postIds);
}
