package org.song.forum.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.song.forum.entity.Notification;
import org.song.forum.entity.NotificationType;
import org.song.forum.entity.User;
import org.song.forum.repository.NotificationRepository;

import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NotificationService 单条已读的归属校验与幂等分支。
 * <p>这些分支集成测试覆盖不到（越权与已读都表现为「无副作用」），
 * 靠 Mockito 验证「是否真的发出了 UPDATE」。</p>
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @InjectMocks
    private NotificationService notificationService;

    private static final Long OWNER_ID = 7L;
    private static final Long NOTIF_ID = 42L;

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
}
