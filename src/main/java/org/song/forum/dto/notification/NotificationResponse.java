package org.song.forum.dto.notification;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知响应
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NotificationResponse {

    private Long id;

    private Long recipientId;

    private Long senderId;
    private String senderUsername;
    private String senderNickname;

    private Long postId;
    private String postTitle;

    /** 被回复评论的 id，仅 REPLY 类型有值 */
    private Long commentId;

    /** REPLY / LIKE / FAVORITE / SYSTEM */
    private String type;

    private String content;

    private Boolean isRead;

    private LocalDateTime createTime;
}
