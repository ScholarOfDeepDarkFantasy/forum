package org.song.forum.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.song.forum.dto.post.PostSummaryResponse;
import org.song.forum.dto.tag.TagResponse;
import org.song.forum.service.PostService;
import org.song.forum.service.TagService;
import org.song.forum.util.PageResponse;
import org.song.forum.util.Result;

/** 标签接口：总览与标签帖子列表（均为公开 GET） */
@RestController
@RequestMapping("/api/tags")
@RequiredArgsConstructor
public class TagController {

    private final TagService tagService;
    private final PostService postService;

    /** 标签总览：?keyword=&page=&size=（按帖子数降序） */
    @GetMapping
    public Result<PageResponse<TagResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        return Result.ok(tagService.list(keyword, Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
    }

    /** 标签帖子列表：?name=&page=&size=（name 用 query 参数，避免标签名含 / 时路径被 Tomcat 拒绝） */
    @GetMapping("/posts")
    public Result<PageResponse<PostSummaryResponse>> posts(
            @RequestParam String name,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(postService.listByTag(name, Math.max(page, 0), Math.min(Math.max(size, 1), 50)));
    }
}
