package org.song.forum.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * 点赞（uk_likes_user_post 保证同一用户对同一帖子只能点赞一次）
 */
@Entity
@Table(name = "likes")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Like extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    private Post post;
}
