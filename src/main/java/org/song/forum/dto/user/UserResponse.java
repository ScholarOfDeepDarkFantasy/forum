package org.song.forum.dto.user;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户公开信息（他人可见）
 */
@Data
@Builder
public class UserResponse {

    private Long id;
    private String username;
    private String nickname;
    private String avatarUrl;
    private String bio;
    private LocalDateTime createTime;
}
