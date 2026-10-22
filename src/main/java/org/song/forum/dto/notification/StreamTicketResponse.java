package org.song.forum.dto.notification;

/**
 * SSE 订阅票据响应。
 *
 * @param ticket    一次性票据（UUID）
 * @param expiresIn 有效期（秒），前端只用于日志/排障，不参与重连决策
 */
public record StreamTicketResponse(String ticket, long expiresIn) {
}
