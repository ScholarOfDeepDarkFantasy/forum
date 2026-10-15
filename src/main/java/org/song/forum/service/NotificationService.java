package org.song.forum.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.song.forum.dto.notification.NotificationResponse;
import org.song.forum.repository.NotificationRepository;
import org.song.forum.util.PageResponse;

/**
 * 通知服务：我的通知列表、未读数、全部已读
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;

    /** 我的通知（时间倒序） */
    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> mine(Long recipientId, Pageable pageable) {
        Page<NotificationResponse> page = notificationRepository
                .findByRecipientIdOrderByCreateTimeDesc(recipientId, pageable)
                .map(this::toResponse);
        return PageResponse.of(page);
    }

    /** 未读数量 */
    @Transactional(readOnly = true)
    public long unreadCount(Long recipientId) {
        return notificationRepository.countByRecipientIdAndIsReadFalse(recipientId);
    }

    /** 全部标记已读 */
    @Transactional
    public void markAllRead(Long recipientId) {
        notificationRepository.markAllRead(recipientId);
    }

    // ---------- 内部方法 ----------

    private NotificationResponse toResponse(org.song.forum.entity.Notification n) {
        return NotificationResponse.builder()
                .id(n.getId())
                .recipientId(n.getRecipient().getId())
                .senderId(n.getSender() == null ? null : n.getSender().getId())
                .senderUsername(n.getSender() == null ? null : n.getSender().getUsername())
                .senderNickname(n.getSender() == null ? null : n.getSender().getNickname())
                .postId(n.getPost() == null ? null : n.getPost().getId())
                .postTitle(n.getPost() == null ? null : n.getPost().getTitle())
                .type(n.getType().name())
                .content(n.getContent())
                .isRead(n.getIsRead())
                .createTime(n.getCreateTime())
                .build();
    }
}
