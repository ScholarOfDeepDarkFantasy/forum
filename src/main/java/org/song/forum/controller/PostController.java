package org.song.forum.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.song.forum.dto.post.PostDetailResponse;
import org.song.forum.dto.post.PostRequest;
import org.song.forum.dto.post.PostSummaryResponse;
import org.song.forum.security.SecurityUtils;
import org.song.forum.service.PostService;
import org.song.forum.util.PageResponse;
import org.song.forum.util.Result;

/**
 * 帖子接口：浏览/搜索（游客），发帖/编辑/删除（登录用户，仅限本人）
 */
@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;

    /** 帖子分页列表：?boardId=&keyword=&page=&size=（游客可访问） */
    @GetMapping
    public Result<PageResponse<PostSummaryResponse>> list(
            @RequestParam(required = false) Long boardId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(postService.list(boardId, keyword, page, Math.min(size, 50)));
    }

    /** 帖子详情：浏览量 +1（游客可访问） */
    @GetMapping("/{id}")
    public Result<PostDetailResponse> detail(@PathVariable Long id) {
        return Result.ok(postService.getDetail(id));
    }

    /** 我的帖子（需登录） */
    @GetMapping("/mine")
    public Result<PageResponse<PostSummaryResponse>> mine(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(postService.myPosts(SecurityUtils.currentUserId(), page, Math.min(size, 50)));
    }

    /** 发帖（需登录） */
    @PostMapping
    public ResponseEntity<Result<PostDetailResponse>> create(@Valid @RequestBody PostRequest req) {
        PostDetailResponse resp = postService.create(SecurityUtils.currentUserId(), req);
        return ResponseEntity.status(HttpStatus.CREATED).body(Result.ok(resp, "发帖成功"));
    }

    /** 编辑帖子（仅作者本人） */
    @PutMapping("/{id}")
    public Result<PostDetailResponse> update(@PathVariable Long id, @Valid @RequestBody PostRequest req) {
        return Result.ok(postService.update(id, SecurityUtils.currentUserId(), req), "帖子已更新");
    }

    /** 删除帖子（仅作者本人，软删除） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        postService.delete(id, SecurityUtils.currentUserId());
        return Result.ok(null, "帖子已删除");
    }
}
