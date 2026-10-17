package org.song.forum.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.song.forum.dto.notification.NotificationResponse;
import org.song.forum.entity.Notification;
import org.song.forum.repository.NotificationRepository;
import org.song.forum.util.PageResponse;

import java.util.NoSuchElementException;

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

    /**
     * 把单条通知标记为已读。
     *
     * <p>先按 id 查一次做归属判断：更新语句影响 0 行时无法区分「不存在」「不属于你」
     * 「本来就已读」三种情况，直接返回会让越权请求看起来像成功。
     *
     * <p>越权时抛 NoSuchElementException（对外 404）而非抢权异常，与「不存在」表现一致，
     * 避免通过状态码探测他人通知 id 是否存在。
     */
    @Transactional
    public void markRead(Long id, Long recipientId) {
        Notification n = notificationRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("通知不存在"));
        if (!n.getRecipient().getId().equals(recipientId)) {
            throw new NoSuchElementException("通知不存在");
        }
        // 本就已读：幂等返回，不重复发 UPDATE
        if (Boolean.TRUE.equals(n.getIsRead())) {
            return;
        }
        notificationRepository.markRead(id, recipientId);
    }

    // ---------- 内部方法 ----------

    private NotificationResponse toResponse(Notification n) {
        return NotificationResponse.builder()
                .id(n.getId())
                .recipientId(n.getRecipient().getId())
                .senderId(n.getSender() == null ? null : n.getSender().getId())
                .senderUsername(n.getSender() == null ? null : n.getSender().getUsername())
                .senderNickname(n.getSender() == null ? null : n.getSender().getNickname())
                .postId(n.getPost() == null ? null : n.getPost().getId())
                .postTitle(n.getPost() == null ? null : n.getPost().getTitle())
                .commentId(n.getComment() == null ? null : n.getComment().getId())
                .type(n.getType().name())
                .content(n.getContent())
                .isRead(n.getIsRead())
                .createTime(n.getCreateTime())
                .build();
    }
}
