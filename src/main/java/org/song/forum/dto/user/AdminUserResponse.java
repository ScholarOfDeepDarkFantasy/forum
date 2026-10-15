package org.song.forum.dto.user;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理员视角用户出参：含状态/禁言截止/角色
 */
@Data
@Builder
public class AdminUserResponse {

    private Long id;
    private String username;
    private String email;
    private String nickname;
    private String status;
    private LocalDateTime mutedUntil;
    private List<String> roles;
    private LocalDateTime createTime;
}
