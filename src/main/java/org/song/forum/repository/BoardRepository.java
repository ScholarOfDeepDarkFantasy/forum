package org.song.forum.repository;

import org.song.forum.entity.Board;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BoardRepository extends JpaRepository<Board, Long> {

    /** 板块列表按 sort_order 升序、id 升序展示 */
    List<Board> findAllByOrderBySortOrderAscIdAsc();

    /** 板块名查重（创建/改名） */
    Optional<Board> findByName(String name);
}
