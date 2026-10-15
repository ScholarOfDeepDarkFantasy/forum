package org.song.forum.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;
import org.song.forum.dto.notification.NotificationResponse;
import org.song.forum.security.SecurityUtils;
import org.song.forum.service.NotificationService;
import org.song.forum.util.PageResponse;
import org.song.forum.util.Result;

import java.util.Map;

/**
 * 通知接口：我的通知列表、未读数、全部已读（均需登录）
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /** 我的通知列表 */
    @GetMapping
    public Result<PageResponse<NotificationResponse>> mine(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 50), Sort.by(Sort.Direction.DESC, "createTime"));
        return Result.ok(notificationService.mine(SecurityUtils.currentUserId(), pageable));
    }

    /** 未读数量 */
    @GetMapping("/unread-count")
    public Result<Map<String, Long>> unreadCount() {
        return Result.ok(Map.of("count", notificationService.unreadCount(SecurityUtils.currentUserId())));
    }

    /** 全部标记已读 */
    @PostMapping("/read-all")
    public Result<Void> readAll() {
        notificationService.markAllRead(SecurityUtils.currentUserId());
        return Result.ok(null, "已全部标记为已读");
    }
}
