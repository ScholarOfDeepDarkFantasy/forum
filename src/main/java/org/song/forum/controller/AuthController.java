package org.song.forum.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.*;
import org.song.forum.dto.auth.AuthResponse;
import org.song.forum.dto.auth.LoginRequest;
import org.song.forum.dto.auth.RegisterRequest;
import org.song.forum.dto.user.UserResponse;
import org.song.forum.security.ClientIpResolver;
import org.song.forum.security.LoginRateLimiter;
import org.song.forum.security.SecurityUtils;
import org.song.forum.service.AuthService;
import org.song.forum.util.Result;

/**
 * 认证接口：注册 / 登录 / 登出 / 当前用户 / 个人资料
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final ClientIpResolver clientIpResolver;
    private final LoginRateLimiter loginRateLimiter;

    /** 注册 */
    @PostMapping("/register")
    public ResponseEntity<Result<AuthResponse>> register(@Valid @RequestBody RegisterRequest req) {
        AuthResponse resp = authService.register(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(Result.ok(resp, "注册成功"));
    }

    /**
     * 登录：登录前先查限流，凭据失败计数，成功清零。
     * <p>参数校验失败（400）与被封禁（400）都不计数：前者不是凭据尝试，后者不该变成
     * 对同一 IP 的额外惩罚。锁定期间直接 429，不校验密码。</p>
     */
    @PostMapping("/login")
    public Result<AuthResponse> login(@Valid @RequestBody LoginRequest req, HttpServletRequest request) {
        String ip = clientIpResolver.resolve(request);
        loginRateLimiter.check(ip);
        try {
            AuthResponse resp = authService.login(req);
            loginRateLimiter.recordSuccess(ip);
            return Result.ok(resp, "登录成功");
        } catch (BadCredentialsException e) {
            loginRateLimiter.recordFailure(ip);
            throw e;
        }
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
