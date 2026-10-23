package org.song.forum.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.song.forum.dto.auth.AuthResponse;
import org.song.forum.dto.auth.LoginRequest;
import org.song.forum.dto.auth.RegisterRequest;
import org.song.forum.dto.user.UserResponse;
import org.song.forum.security.SecurityUtils;
import org.song.forum.service.AuthService;
import org.song.forum.util.Result;

/**
 * 认证接口：注册 / 登录 / 当前用户 / 个人资料
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 注册 */
    @PostMapping("/register")
    public ResponseEntity<Result<AuthResponse>> register(@Valid @RequestBody RegisterRequest req) {
        AuthResponse resp = authService.register(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(Result.ok(resp, "注册成功"));
    }

    /** 登录 */
    @PostMapping("/login")
    public Result<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        return Result.ok(authService.login(req), "登录成功");
    }

    /** 登出（需登录）：递增 token 版本，旧 token 立即失效 */
    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout(SecurityUtils.currentUserId());
        return Result.ok(null, "已退出登录");
    }

    /** 当前登录用户信息（需携带 Token） */
    @GetMapping("/me")
    public Result<AuthResponse> me() {
        return Result.ok(authService.me(SecurityUtils.currentUserId()));
    }

    /** 更新个人资料（需登录） */
    @PutMapping("/profile")
    public Result<UserResponse> updateProfile(@Valid @RequestBody org.song.forum.dto.user.UpdateProfileRequest req) {
        return Result.ok(authService.updateProfile(SecurityUtils.currentUserId(), req), "资料已更新");
    }

    /** 用户公开主页信息（游客可访问） */
    @GetMapping("/users/{id}")
    public Result<UserResponse> publicProfile(@PathVariable Long id) {
        return Result.ok(authService.getPublicProfile(id));
    }
}
