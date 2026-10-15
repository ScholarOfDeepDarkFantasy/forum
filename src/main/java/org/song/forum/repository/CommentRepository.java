package org.song.forum.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.song.forum.entity.Comment;

import java.util.Optional;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    /** 帖子下的顶级评论分页（parent 为 null），抓取 author 与 parent.author */
    @EntityGraph(attributePaths = {"author", "parent", "parent.author"})
    Page<Comment> findByPostIdAndParentIsNullAndDeletedFalse(Long postId, Pageable pageable);

    /** 某顶级评论下的楼中楼回复 */
    @EntityGraph(attributePaths = {"author"})
    java.util.List<Comment> findByParentIdAndDeletedFalseOrderByIdAsc(Long parentId);

    /** 帖子评论总数（软删除不计） */
    long countByPostIdAndDeletedFalse(Long postId);

    @EntityGraph(attributePaths = {"author", "parent"})
    Optional<Comment> findWithDetailsById(Long id);
}
