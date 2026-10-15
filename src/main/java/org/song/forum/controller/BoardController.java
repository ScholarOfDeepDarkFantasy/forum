package org.song.forum.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.song.forum.dto.board.BoardRequest;
import org.song.forum.dto.board.BoardResponse;
import org.song.forum.service.BoardService;
import org.song.forum.util.Result;

import java.util.List;

/**
 * 板块接口：列表/详情（游客），增删改（管理员，权限在第 5 阶段接 AdminOnly）
 */
@RestController
@RequestMapping("/api/boards")
@RequiredArgsConstructor
public class BoardController {

    private final BoardService boardService;

    /** 板块列表（游客可访问） */
    @GetMapping
    public Result<List<BoardResponse>> list() {
        return Result.ok(boardService.listAll());
    }

    /** 板块详情（游客可访问） */
    @GetMapping("/{id}")
    public Result<BoardResponse> detail(@PathVariable Long id) {
        return Result.ok(boardService.getById(id));
    }

    /** 创建板块（管理员） */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<BoardResponse>> create(@Valid @RequestBody BoardRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Result.ok(boardService.create(req), "板块已创建"));
    }

    /** 更新板块（管理员） */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<BoardResponse> update(@PathVariable Long id, @Valid @RequestBody BoardRequest req) {
        return Result.ok(boardService.update(id, req), "板块已更新");
    }

    /** 删除板块（管理员；板块下有帖子时拒绝） */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        boardService.delete(id);
        return Result.ok(null, "板块已删除");
    }
}
