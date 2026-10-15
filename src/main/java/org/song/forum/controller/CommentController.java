package org.song.forum.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.song.forum.dto.comment.CommentRequest;
import org.song.forum.dto.comment.CommentResponse;
import org.song.forum.security.SecurityUtils;
import org.song.forum.service.CommentService;
import org.song.forum.util.PageResponse;
import org.song.forum.util.Result;

/**
 * 评论接口：帖子下评论树（游客可读），发表/删除（登录用户，仅限本人）
 */
@RestController
@RequestMapping("/api/posts/{postId}/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    /** 帖子评论树：?page=&size=（游客可访问） */
    @GetMapping
    public Result<PageResponse<CommentResponse>> list(
            @PathVariable Long postId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(commentService.listByPost(postId, page, Math.min(size, 50)));
    }

    /** 发表评论/回复（需登录） */
    @PostMapping
    public ResponseEntity<Result<CommentResponse>> create(@PathVariable Long postId,
                                                          @Valid @RequestBody CommentRequest req) {
        CommentResponse resp = commentService.create(postId, SecurityUtils.currentUserId(), req);
        return ResponseEntity.status(HttpStatus.CREATED).body(Result.ok(resp, "评论成功"));
    }

    /** 删除评论（作者本人或管理员/版主，软删除占位） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long postId, @PathVariable Long id) {
        commentService.delete(id, SecurityUtils.currentUserId());
        return Result.ok(null, "评论已删除");
    }
}
