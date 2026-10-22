package org.song.forum.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.song.forum.dto.notification.NotificationResponse;
import org.song.forum.entity.Comment;
import org.song.forum.entity.Notification;
import org.song.forum.entity.NotificationType;
import org.song.forum.entity.Post;
import org.song.forum.entity.User;
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
    private final NotificationStreamService notificationStreamService;

    // ---------- 供其它服务调用的创建入口 ----------

    /**
     * 创建一条通知，并在事务提交后实时推送给接收者。
     *
     * <p>为什么必须等提交：提交前推送，接收者点开时可能还查不到这条通知；
     * 如果事务随后回滚，更会推出一条数据库里根本不存在的通知。
     *
     * <p>没有事务同步时（单元测试、或将来某个非事务调用方）直接推送：
     * 通知只能「迟到」，不能「丢失」。
     *
     * @return 已持久化的通知实体，供调用方记日志或测试断言
     */
    public Notification create(User recipient, User sender, Post post, Comment comment,
                               NotificationType type, String content) {
        Notification notification = notificationRepository.save(Notification.builder()
                .recipient(recipient)
                .sender(sender)
                .post(post)
                .comment(comment)
                .type(type)
                .content(content)
                .build());

        // 先在事务内把响应算好：懒加载字段在这里都还能读到
        NotificationResponse payload = toResponse(notification);
        Long recipientId = recipient.getId();

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    notificationStreamService.push(recipientId, payload);
                }
            });
        } else {
            notificationStreamService.push(recipientId, payload);
        }
        return notification;
    }

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
