package org.song.forum.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.song.forum.dto.user.AdminUserResponse;
import org.song.forum.dto.user.UserStatusRequest;
import org.song.forum.entity.User;
import org.song.forum.entity.UserStatus;
import org.song.forum.exception.BusinessException;
import org.song.forum.repository.UserRepository;
import org.song.forum.util.PageResponse;

/**
 * 管理员用户管理：用户列表、禁言/封禁/解封。
 * 管理员不可操作其他管理员，防止误封管理员账号。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;

    /** 用户列表（时间倒序） */
    @Transactional(readOnly = true)
    public PageResponse<AdminUserResponse> list(int page, int size) {
        Page<AdminUserResponse> result = userRepository
                .findAll(PageRequest.of(page, Math.min(size, 50), Sort.by(Sort.Direction.DESC, "createTime")))
                .map(this::toResponse);
        return PageResponse.of(result);
    }

    /** 设置用户状态：禁言（MUTED）/封禁（BANNED）/解封（ACTIVE） */
    @Transactional
    public AdminUserResponse changeStatus(Long targetUserId, UserStatusRequest req) {
        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> new BusinessException("用户不存在"));

        if (user.getRoles().stream().anyMatch(r -> "ADMIN".equals(r.getName()))) {
            throw new BusinessException("不能对管理员执行此操作");
        }

        UserStatus status;
        try {
            status = UserStatus.valueOf(req.getStatus());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("非法状态，允许值：ACTIVE / MUTED / BANNED");
        }

        switch (status) {
            case MUTED -> {
                if (req.getMuteMinutes() == null) {
                    throw new BusinessException("禁言必须指定时长（分钟）");
                }
                user.setStatus(UserStatus.MUTED);
                user.setMutedUntil(java.time.LocalDateTime.now().plusMinutes(req.getMuteMinutes()));
            }
            case BANNED -> {
                user.setStatus(UserStatus.BANNED);
                user.setMutedUntil(null);
                // 封禁要立刻踢掉已签发的 token：只改 status 的话过滤器要等版本变化才拒绝
                user.setTokenVersion(user.getTokenVersion() + 1);
            }
            case ACTIVE -> {
                user.setStatus(UserStatus.ACTIVE);
                user.setMutedUntil(null);
            }
        }
        user = userRepository.save(user);
        log.info("用户 {} 状态被改为 {}", user.getUsername(), status);
        return toResponse(user);
    }

    // ---------- 内部方法 ----------

    private AdminUserResponse toResponse(User user) {
        return AdminUserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .nickname(user.getNickname())
                .status(user.getStatus().name())
                .mutedUntil(user.getMutedUntil())
                .roles(user.getRoles().stream().map(r -> r.getName()).toList())
                .createTime(user.getCreateTime())
                .build();
    }
}
