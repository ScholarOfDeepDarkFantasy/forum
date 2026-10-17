package org.song.forum.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.song.forum.entity.Notification;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** 我的通知列表，抓取 sender/post 标题与 comment 锚点所需关联（comment 多为 null，实为 left join） */
    @EntityGraph(attributePaths = {"sender", "post", "comment"})
    Page<Notification> findByRecipientIdOrderByCreateTimeDesc(Long recipientId, Pageable pageable);

    /** 未读数量 */
    long countByRecipientIdAndIsReadFalse(Long recipientId);

    /** 全部标记已读 */
    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true WHERE n.recipient.id = :recipientId AND n.isRead = false")
    void markAllRead(@Param("recipientId") Long recipientId);

    /** 单条已读：带 recipientId 条件，防止越权改他人通知 */
    @Modifying
    @Query("""
            UPDATE Notification n SET n.isRead = true
            WHERE n.id = :id AND n.recipient.id = :recipientId AND n.isRead = false
            """)
    int markRead(@Param("id") Long id, @Param("recipientId") Long recipientId);
}
