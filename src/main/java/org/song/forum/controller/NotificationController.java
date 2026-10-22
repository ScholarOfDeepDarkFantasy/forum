package org.song.forum.controller;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.song.forum.dto.notification.NotificationResponse;
import org.song.forum.dto.notification.StreamTicketResponse;
import org.song.forum.exception.InvalidStreamTicketException;
import org.song.forum.security.SecurityUtils;
import org.song.forum.service.NotificationService;
import org.song.forum.service.NotificationStreamService;
import org.song.forum.service.StreamTicketService;
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
    private final NotificationStreamService notificationStreamService;
    private final StreamTicketService streamTicketService;

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

    /** 单条标记已读（仅限本人的通知） */
    @PostMapping("/{id}/read")
    public Result<Void> markRead(@PathVariable Long id) {
        notificationService.markRead(id, SecurityUtils.currentUserId());
        return Result.ok(null, "已标记为已读");
    }

    /**
     * 下发一次性 SSE 订阅票据（需登录）。
     * <p>前端拿到 ticket 后拼到 EventSource 的 query 上；ticket 60 秒有效、
     * 用一次即焚，因此不需要也不应该再校验 JWT。</p>
     */
    @PostMapping("/stream-ticket")
    public Result<StreamTicketResponse> streamTicket() {
        Long userId = SecurityUtils.currentUserId();
        return Result.ok(new StreamTicketResponse(
                streamTicketService.issue(userId), streamTicketService.ttlSeconds()));
    }

    /**
     * SSE 订阅。ticket 由上面的接口签发；本端点匿名可达（SecurityConfig 显式放行），
     * 鉴权完全由 ticket 承担。
     *
     * <p>返回裸 SseEmitter（不包 ResponseEntity）：Spring 的异步返回值处理器对
     * SseEmitter 的支持最直接；响应头在进入异步前用 response 一次性设置。
     * {@code X-Accel-Buffering: no} 是给 nginx 的兜底信号——即使某天 snippet
     * 漏了 proxy_buffering off，也不会攒住事件。</p>
     *
     * <p>异常必须在返回 SseEmitter 之前抛出：一旦返回 emitter，响应就进入异步流程，
     * 再抛异常只能走 error 回调，客户端看到的是 200 + 断流，而不是 401。</p>
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(required = false) String ticket,
                             HttpServletResponse response) {
        Long userId = streamTicketService.consume(ticket);
        if (userId == null) {
            throw new InvalidStreamTicketException("通知连接凭证无效或已过期");
        }
        // 必须在返回 emitter（进入异步）之前设置响应头，之后再改不会发给客户端
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        return notificationStreamService.register(userId);
    }
}
