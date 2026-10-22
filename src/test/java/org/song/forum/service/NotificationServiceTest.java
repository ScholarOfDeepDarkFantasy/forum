package org.song.forum.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.song.forum.dto.notification.NotificationResponse;
import org.song.forum.entity.Comment;
import org.song.forum.entity.Notification;
import org.song.forum.entity.NotificationType;
import org.song.forum.entity.Post;
import org.song.forum.entity.User;
import org.song.forum.repository.NotificationRepository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NotificationService 的行为单测：单条已读的归属校验与幂等分支，以及 create 的推送时机与失败隔离。
 * <p>已读分支集成测试覆盖不到（越权与已读都表现为「无副作用」），靠 Mockito 验证「是否真的发出了 UPDATE」；
 * create 的 afterCommit/回滚/无事务分支也只能在单测里精确控制事务同步——集成测试无法区分
 * 「提交前」与「提交后」，只能验证「收到帧时通知已可见」。</p>
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private NotificationStreamService notificationStreamService;

    @InjectMocks
    private NotificationService notificationService;

    private static final Long OWNER_ID = 7L;
    private static final Long NOTIF_ID = 42L;
    private static final Long RECIPIENT_ID = 11L;
    private static final Long POST_ID = 5L;
    private static final Long COMMENT_ID = 33L;

    private Notification notification(Long recipientId, boolean isRead) {
        User recipient = new User();
        recipient.setId(recipientId);
        return Notification.builder()
                .recipient(recipient)
                .type(NotificationType.SYSTEM)
                .content("测试通知")
                .isRead(isRead)
                .build();
    }

    /** toResponse 会读 username/nickname，这里造一个最小可用的用户 */
    private User user(Long id, String username) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setNickname(username + "的昵称");
        return u;
    }

    private Post post() {
        // BaseEntity 的 id 不在 @Builder 里（Lombok 不收集父类字段），builder 后补 set
        Post p = Post.builder().title("测试帖标题").build();
        p.setId(POST_ID);
        return p;
    }

    @Test
    void markRead_通知不存在_抛NoSuchElementException() {
        when(notificationRepository.findById(NOTIF_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.markRead(NOTIF_ID, OWNER_ID))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("通知不存在");

        // 关键：查不到时绝不能发出 UPDATE
        verify(notificationRepository, never()).markRead(anyLong(), anyLong());
    }

    @Test
    void markRead_通知属于他人_抛NoSuchElementException且不发UPDATE() {
        when(notificationRepository.findById(NOTIF_ID)).thenReturn(Optional.of(notification(999L, false)));

        assertThatThrownBy(() -> notificationService.markRead(NOTIF_ID, OWNER_ID))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("通知不存在");

        verify(notificationRepository, never()).markRead(anyLong(), anyLong());
    }

    @Test
    void markRead_已读通知_幂等返回不重复UPDATE() {
        when(notificationRepository.findById(NOTIF_ID)).thenReturn(Optional.of(notification(OWNER_ID, true)));

        notificationService.markRead(NOTIF_ID, OWNER_ID);

        verify(notificationRepository, never()).markRead(anyLong(), anyLong());
    }

    @Test
    void markRead_自己的未读通知_执行UPDATE() {
        when(notificationRepository.findById(NOTIF_ID)).thenReturn(Optional.of(notification(OWNER_ID, false)));
        when(notificationRepository.markRead(any(), any())).thenReturn(1);

        notificationService.markRead(NOTIF_ID, OWNER_ID);

        verify(notificationRepository).markRead(NOTIF_ID, OWNER_ID);
    }

    // ---------- create：实时推送时机 ----------

    /**
     * 没有事务同步（例如单元测试或将来某个非事务调用方）时，
     * 必须在保存后立刻推送——不能因为「等 afterCommit」把通知静默丢掉。
     */
    @Test
    void create_无事务时保存后直接推送一次() {
        User recipient = user(RECIPIENT_ID, "recipient");
        User sender = user(2L, "sender");
        Post post = post();
        Notification saved = Notification.builder()
                .recipient(recipient)
                .sender(sender)
                .post(post)
                .type(NotificationType.LIKE)
                .content("sender 赞了你的帖子「测试帖标题」")
                .isRead(false)
                .build();
        saved.setId(77L);
        when(notificationRepository.save(any(Notification.class))).thenReturn(saved);

        notificationService.create(recipient, sender, post, null, NotificationType.LIKE,
                "sender 赞了你的帖子「测试帖标题」");

        ArgumentCaptor<NotificationResponse> captor = ArgumentCaptor.forClass(NotificationResponse.class);
        verify(notificationStreamService).push(eq(RECIPIENT_ID), captor.capture());
        NotificationResponse payload = captor.getValue();
        assertThat(payload.getId()).isEqualTo(77L);
        assertThat(payload.getRecipientId()).isEqualTo(RECIPIENT_ID);
        assertThat(payload.getSenderId()).isEqualTo(2L);
        assertThat(payload.getSenderUsername()).isEqualTo("sender");
        assertThat(payload.getSenderNickname()).isEqualTo("sender的昵称");
        assertThat(payload.getPostId()).isEqualTo(POST_ID);
        assertThat(payload.getPostTitle()).isEqualTo("测试帖标题");
        // LIKE 没有关联评论：commentId 必须是 null，不能把「没有」序列化成 0 或空串
        assertThat(payload.getCommentId()).isNull();
        assertThat(payload.getContent()).isEqualTo("sender 赞了你的帖子「测试帖标题」");
        assertThat(payload.getType()).isEqualTo("LIKE");
        assertThat(payload.getIsRead()).isFalse();
    }

    /**
     * 有事务同步时只能注册回调：提交前推送会让接收者拉到「还没提交」的通知，
     * 事务回滚时更会推一条根本不存在的通知。
     */
    @Test
    void create_存在事务同步时提交后才推送() {
        User recipient = user(RECIPIENT_ID, "recipient");
        User sender = user(2L, "sender");
        Post post = post();
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> {
            Notification n = invocation.getArgument(0);
            n.setId(88L);
            return n;
        });

        TransactionSynchronizationManager.initSynchronization();
        try {
            notificationService.create(recipient, sender, post, null, NotificationType.REPLY,
                    "sender 回复了你的评论");

            // 提交前：一次都不能推
            verify(notificationStreamService, never()).push(anyLong(), any(NotificationResponse.class));

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(notificationStreamService).push(eq(RECIPIENT_ID), any(NotificationResponse.class));
    }

    /** 事务回滚：afterCommit 不会执行，因此一条也不能推 */
    @Test
    void create_事务回滚时不推送() {
        User recipient = user(RECIPIENT_ID, "recipient");
        Post post = post();
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> {
            Notification n = invocation.getArgument(0);
            n.setId(99L);
            return n;
        });

        TransactionSynchronizationManager.initSynchronization();
        try {
            notificationService.create(recipient, user(2L, "sender"), post, null,
                    NotificationType.FAVORITE, "sender 收藏了你的帖子");
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(notificationStreamService, never()).push(anyLong(), any(NotificationResponse.class));
    }

    /** 入库的实体必须带接收者/发送者/帖子/类型/内容，默认未读；无评论时 comment 为 null */
    @Test
    void create_保存的通知字段完整且默认未读() {
        User recipient = user(RECIPIENT_ID, "recipient");
        User sender = user(2L, "sender");
        Post post = post();
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> {
            Notification n = invocation.getArgument(0);
            n.setId(100L);
            return n;
        });

        notificationService.create(recipient, sender, post, null,
                NotificationType.FAVORITE, "sender 收藏了你的帖子");

        verify(notificationRepository).save(captor.capture());
        Notification n = captor.getValue();
        assertThat(n.getRecipient()).isSameAs(recipient);
        assertThat(n.getSender()).isSameAs(sender);
        assertThat(n.getPost()).isSameAs(post);
        assertThat(n.getComment()).isNull();
        assertThat(n.getType()).isEqualTo(NotificationType.FAVORITE);
        assertThat(n.getContent()).contains("收藏");
        assertThat(n.getIsRead()).isFalse();
        verify(notificationStreamService).push(eq(RECIPIENT_ID), any(NotificationResponse.class));
    }

    /** REPLY 通知必须把被回复的评论带进实体，并把 commentId 映射进推送负载 */
    @Test
    void create_REPLY通知携带被回复评论id() {
        User recipient = user(RECIPIENT_ID, "recipient");
        User sender = user(2L, "sender");
        Post post = post();
        Comment parent = Comment.builder()
                .post(post)
                .author(recipient)
                .content("原评论")
                .build();
        parent.setId(COMMENT_ID);
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> {
            Notification n = invocation.getArgument(0);
            n.setId(101L);
            return n;
        });

        notificationService.create(recipient, sender, post, parent,
                NotificationType.REPLY, "sender 回复了你的评论");

        ArgumentCaptor<Notification> entityCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getComment()).isSameAs(parent);

        ArgumentCaptor<NotificationResponse> payloadCaptor = ArgumentCaptor.forClass(NotificationResponse.class);
        verify(notificationStreamService).push(eq(RECIPIENT_ID), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().getCommentId()).isEqualTo(COMMENT_ID);
        assertThat(payloadCaptor.getValue().getType()).isEqualTo("REPLY");
    }

    /**
     * 推送失败必须被隔离：afterCommit 抛异常会冒泡回提交线程（数据已提交、请求却 500），
     * 非事务调用方也不该因为一次 SSE 写失败而丢掉已经入库的通知。
     * <p>同时断言 WARN 日志真的带上了异常上下文（e.getMessage() 与堆栈代理），不是一条无信息的日志。</p>
     */
    @Test
    void create_推送抛异常时被隔离且仍返回通知() {
        User recipient = user(RECIPIENT_ID, "recipient");
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> {
            Notification n = invocation.getArgument(0);
            n.setId(110L);
            return n;
        });
        doThrow(new RuntimeException("SSE 连接注册表异常"))
                .when(notificationStreamService).push(anyLong(), any(NotificationResponse.class));

        Logger logger = (Logger) LoggerFactory.getLogger(NotificationService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            // 无事务同步：立即推送分支
            Notification immediate = notificationService.create(recipient, user(2L, "sender"), post(), null,
                    NotificationType.LIKE, "sender 赞了你的帖子");

            // 有事务同步：afterCommit 分支（异常同样不能抛回提交线程）
            TransactionSynchronizationManager.initSynchronization();
            try {
                Notification afterCommit = notificationService.create(recipient, user(3L, "replyer"), post(), null,
                        NotificationType.FAVORITE, "replyer 收藏了你的帖子");
                assertThat(afterCommit).isNotNull();
                assertThatCode(() -> TransactionSynchronizationManager.getSynchronizations()
                        .forEach(TransactionSynchronization::afterCommit))
                        .doesNotThrowAnyException();
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }

            assertThat(immediate).isNotNull();
            assertThat(immediate.getId()).isEqualTo(110L);
        } finally {
            logger.detachAppender(appender);
        }

        verify(notificationStreamService, times(2)).push(eq(RECIPIENT_ID), any(NotificationResponse.class));
        assertThat(appender.list)
                .as("两次推送失败都应记录 WARN，且日志保留异常信息")
                .hasSize(2)
                .allSatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage()).contains("实时推送失败");
                    assertThat(event.getThrowableProxy()).isNotNull();
                    assertThat(event.getThrowableProxy().getClassName())
                            .isEqualTo(RuntimeException.class.getName());
                    assertThat(event.getThrowableProxy().getMessage()).contains("SSE 连接注册表异常");
                });
    }
}
