package org.song.forum.dto.user;

import lombok.Builder;
import lombok.Data;
import org.song.forum.entity.User;

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

    /** 实体 → 公开信息：AuthService 与 AvatarService 共用，避免两处实现漂移 */
    public static UserResponse from(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatarUrl(user.getAvatarUrl())
                .bio(user.getBio())
                .createTime(user.getCreateTime())
                .build();
    }
}
