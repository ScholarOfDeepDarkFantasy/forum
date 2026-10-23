package org.song.forum.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.song.forum.dto.auth.AuthResponse;
import org.song.forum.dto.auth.LoginRequest;
import org.song.forum.dto.auth.RegisterRequest;
import org.song.forum.dto.user.UpdateProfileRequest;
import org.song.forum.dto.user.UserResponse;
import org.song.forum.entity.User;
import org.song.forum.entity.UserStatus;
import org.song.forum.exception.BusinessException;
import org.song.forum.repository.RoleRepository;
import org.song.forum.repository.UserRepository;
import org.song.forum.security.ForumUserDetails;
import org.song.forum.security.JwtTokenProvider;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 认证与个人资料服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    /** 注册：用户名/邮箱查重 → BCrypt 加密 → 赋默认 USER 角色 → 签发 Token */
    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (userRepository.existsByUsername(req.getUsername())) {
            throw new BusinessException("用户名已被占用");
        }
        if (userRepository.existsByEmail(req.getEmail())) {
            throw new BusinessException("邮箱已被注册");
        }

        User user = User.builder()
                .username(req.getUsername())
                .password(passwordEncoder.encode(req.getPassword()))
                .email(req.getEmail())
                .nickname(req.getNickname() != null ? req.getNickname() : req.getUsername())
                .status(UserStatus.ACTIVE)
                .build();
        user.getRoles().add(roleRepository.findByName("USER")
                .orElseThrow(() -> new BusinessException("系统缺少 USER 角色，请检查 Flyway 种子数据")));
        user = userRepository.save(user);
        log.info("新用户注册: {}", user.getUsername());

        return buildAuthResponse(user);
    }

    /** 登录：认证 → 状态检查（封禁/禁言）→ 签发 Token */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        User user = userRepository.findByUsername(req.getUsername())
                .orElseThrow(() -> new BadCredentialsException("用户名或密码错误"));

        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw new BadCredentialsException("用户名或密码错误");
        }
        checkStatus(user);

        return buildAuthResponse(user);
    }

    /** 当前登录用户信息 */
    @Transactional(readOnly = true)
    public AuthResponse me(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("用户不存在"));
        return buildAuthResponse(user);
    }

    /** 更新个人资料 */
    @Transactional
    public UserResponse updateProfile(Long userId, UpdateProfileRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("用户不存在"));
        if (req.getNickname() != null) user.setNickname(req.getNickname());
        if (req.getBio() != null) user.setBio(req.getBio());
        user = userRepository.save(user);
        return UserResponse.from(user);
    }

    /** 用户公开信息 */
    @Transactional(readOnly = true)
    public UserResponse getPublicProfile(Long userId) {
        return userRepository.findById(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> new BusinessException("用户不存在"));
    }

    // ---------- 内部方法 ----------

    /** 封禁/禁言状态检查：封禁拒绝登录；禁言允许登录但返回状态由前端限制发帖 */
    private void checkStatus(User user) {
        if (user.getStatus() == UserStatus.BANNED) {
            throw new BusinessException("账号已被封禁，请联系管理员");
        }
        if (user.getStatus() == UserStatus.MUTED
                && user.getMutedUntil() != null
                && user.getMutedUntil().isBefore(LocalDateTime.now())) {
            // 禁言到期自动恢复
            user.setStatus(UserStatus.ACTIVE);
            user.setMutedUntil(null);
            userRepository.save(user);
        }
    }

    private AuthResponse buildAuthResponse(User user) {
        List<String> roles = user.getRoles().stream().map(r -> r.getName()).toList();
        String token = jwtTokenProvider.createToken(
                user.getId(), user.getUsername(), roles, user.getTokenVersion());
        return AuthResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .expiresAt(System.currentTimeMillis() + jwtTokenProvider.getExpirationMs())
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .nickname(user.getNickname())
                .avatarUrl(user.getAvatarUrl())
                .roles(roles)
                .status(user.getStatus().name())
                .mutedUntil(user.getMutedUntil())
                .build();
    }
}
