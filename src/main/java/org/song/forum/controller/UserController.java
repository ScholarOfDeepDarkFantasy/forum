package org.song.forum.controller;

import lombok.RequiredArgsConstructor;
import org.song.forum.dto.user.UserResponse;
import org.song.forum.security.SecurityUtils;
import org.song.forum.service.AvatarService;
import org.song.forum.util.Result;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 当前用户的头像接口。只有 POST/DELETE，不落在任何公开规则里，
 * 因此由 SecurityConfig 的 anyRequest().authenticated() 兜住。
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final AvatarService avatarService;

    @PostMapping("/me/avatar")
    public Result<UserResponse> uploadAvatar(@RequestParam("file") MultipartFile file) {
        return Result.ok(avatarService.upload(SecurityUtils.currentUserId(), file), "头像已更新");
    }

    @DeleteMapping("/me/avatar")
    public Result<UserResponse> removeAvatar() {
        return Result.ok(avatarService.remove(SecurityUtils.currentUserId()), "头像已移除");
    }
}
