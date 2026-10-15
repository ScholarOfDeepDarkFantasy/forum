package org.song.forum.dto.auth;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 登录/注册响应：JWT Token + 用户基本信息
 */
@Data
@Builder
public class AuthResponse {

    private String token;
    private String tokenType;      // 固定 "Bearer"
    private Long expiresAt;        // 过期时间戳（毫秒）

    private Long id;
    private String username;
    private String email;
    private String nickname;
    private String avatarUrl;
    private List<String> roles;    // 如 ["USER"]
    private String status;         // ACTIVE / MUTED / BANNED
    private LocalDateTime mutedUntil;
}
