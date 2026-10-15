package org.song.forum.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.song.forum.dto.user.AdminUserResponse;
import org.song.forum.dto.user.UserStatusRequest;
import org.song.forum.service.AdminUserService;
import org.song.forum.util.PageResponse;
import org.song.forum.util.Result;

/**
 * 管理员接口：用户列表、禁言/封禁/解封（仅 ADMIN）
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final AdminUserService adminUserService;

    /** 用户列表：?page=&size= */
    @GetMapping
    public Result<PageResponse<AdminUserResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(adminUserService.list(page, size));
    }

    /** 禁言/封禁/解封：status = MUTED(带 muteMinutes) / BANNED / ACTIVE */
    @PutMapping("/{id}/status")
    public Result<AdminUserResponse> changeStatus(@PathVariable Long id,
                                                  @Valid @RequestBody UserStatusRequest req) {
        return Result.ok(adminUserService.changeStatus(id, req), "用户状态已更新");
    }
}
