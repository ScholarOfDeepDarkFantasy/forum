package org.song.forum.repository;

import org.song.forum.entity.Favorite;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FavoriteRepository extends JpaRepository<Favorite, Long> {

    Optional<Favorite> findByUserIdAndPostId(Long userId, Long postId);

    /** 我的收藏列表 */
    @EntityGraph(attributePaths = {"post", "post.board", "post.author"})
    Page<Favorite> findByUserIdOrderByCreateTimeDesc(Long userId, Pageable pageable);
}
