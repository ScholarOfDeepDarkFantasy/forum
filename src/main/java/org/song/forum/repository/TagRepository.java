package org.song.forum.repository;

import org.song.forum.entity.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TagRepository extends JpaRepository<Tag, Long> {

    Optional<Tag> findByName(String name);

    /**
     * 标签聚合：按未删除帖子的引用次数统计。
     * 返回 Object[] {id, name, count}，聚合与分页在服务层做——group by 与 Pageable 同用时
     * Spring Data 派生的 count 查询容易出错，标签量级小，服务层排序分页更稳。
     */
    @Query("""
            SELECT t.id, t.name, COUNT(p.id) FROM Post p JOIN p.tags t
            WHERE p.deleted = false
              AND (:keyword = '' OR LOWER(t.name) LIKE LOWER(CONCAT('%', :keyword, '%')))
            GROUP BY t.id, t.name
            """)
    List<Object[]> findWithPostCounts(@Param("keyword") String keyword);
}
