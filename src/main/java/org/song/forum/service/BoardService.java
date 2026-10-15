package org.song.forum.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.song.forum.dto.board.BoardRequest;
import org.song.forum.dto.board.BoardResponse;
import org.song.forum.entity.Board;
import org.song.forum.exception.BusinessException;
import org.song.forum.repository.BoardRepository;
import org.song.forum.repository.PostRepository;

import java.util.List;

/**
 * 板块服务：游客浏览 + 管理员 CRUD
 */
@Service
@RequiredArgsConstructor
public class BoardService {

    private final BoardRepository boardRepository;
    private final PostRepository postRepository;

    /** 板块列表（按 sortOrder 升序），附带每个板块的帖子数 */
    @Transactional(readOnly = true)
    public List<BoardResponse> listAll() {
        return boardRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    /** 板块详情 */
    @Transactional(readOnly = true)
    public BoardResponse getById(Long id) {
        return boardRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new BusinessException("板块不存在"));
    }

    /** 创建板块（管理员） */
    @Transactional
    public BoardResponse create(BoardRequest req) {
        boardRepository.findByName(req.getName()).ifPresent(b -> {
            throw new BusinessException("板块名已存在");
        });
        Board board = Board.builder()
                .name(req.getName())
                .description(req.getDescription())
                .sortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0)
                .build();
        return toResponse(boardRepository.save(board));
    }

    /** 更新板块（管理员） */
    @Transactional
    public BoardResponse update(Long id, BoardRequest req) {
        Board board = boardRepository.findById(id)
                .orElseThrow(() -> new BusinessException("板块不存在"));
        // 改名时查重（排除自身）
        boardRepository.findByName(req.getName())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new BusinessException("板块名已存在");
                });
        board.setName(req.getName());
        board.setDescription(req.getDescription());
        if (req.getSortOrder() != null) {
            board.setSortOrder(req.getSortOrder());
        }
        return toResponse(boardRepository.save(board));
    }

    /** 删除板块（管理员）。MVP 策略：板块下仍有帖子时禁止删除，防级联误删 */
    @Transactional
    public void delete(Long id) {
        if (!boardRepository.existsById(id)) {
            throw new BusinessException("板块不存在");
        }
        if (postRepository.countByBoardIdAndDeletedFalse(id) > 0) {
            throw new BusinessException("板块下仍有帖子，不能删除");
        }
        boardRepository.deleteById(id);
    }

    private BoardResponse toResponse(Board board) {
        return BoardResponse.builder()
                .id(board.getId())
                .name(board.getName())
                .description(board.getDescription())
                .sortOrder(board.getSortOrder())
                .postCount(postRepository.countByBoardIdAndDeletedFalse(board.getId()))
                .createTime(board.getCreateTime())
                .build();
    }
}
