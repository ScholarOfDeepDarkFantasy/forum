package org.song.forum.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * 用户
 */
@Entity
@Table(name = "users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User extends BaseEntity {

    @Column(nullable = false, length = 50, unique = true)
    private String username;

    /** BCrypt 密文，永远不通过 DTO 对外输出 */
    @Column(nullable = false, length = 100)
    private String password;

    @Column(nullable = false, length = 100, unique = true)
    private String email;

    @Column(length = 50)
    private String nickname;

    @Column(name = "avatar_url", length = 255)
    private String avatarUrl;

    @Column(length = 500)
    private String bio;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    /** 禁言截止时间，仅 MUTED 状态下有意义 */
    @Column(name = "muted_until")
    private LocalDateTime mutedUntil;
}
