package org.song.forum.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 订阅票据：一次性、短 TTL、只存在服务端内存里。
 *
 * <p>为什么需要 ticket：浏览器的 EventSource 不能设置请求头，JWT 只能拼进 URL；
 * 而 URL 会落进 nginx access log、浏览器历史与 Referer。ticket 泄露窗口只有 TTL
 * （默认 60 秒）且用一次即焚，风险远小于把 24 小时有效的 JWT 挂在 ?token= 上。</p>
 *
 * <p>清理策略：每次签发顺带删除已过期条目，消费时也立即删除；因此 map 大小
 * 上限约等于「TTL 内签发的票据数」，不会随运行时长无限增长。</p>
 */
@Service
public class StreamTicketService {

    private final long ttlMs;
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    public StreamTicketService(@Value("${app.sse.ticket-ttl-ms:60000}") long ttlMs) {
        this.ttlMs = ttlMs;
    }

    /** 签发一张 ticket（同一用户可同时持有多张，对应多个标签页） */
    public String issue(Long userId) {
        purgeExpired();
        String ticket = UUID.randomUUID().toString();
        tickets.put(ticket, new Ticket(userId, System.currentTimeMillis() + ttlMs));
        return ticket;
    }

    /**
     * 消费 ticket：有效则返回 userId 并立刻删除（一次性）。
     * 空值、未知、已用、过期一律返回 null，由调用方统一转 401。
     */
    public Long consume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return null;
        }
        Ticket found = tickets.remove(ticket);
        if (found == null || found.expiresAt() < System.currentTimeMillis()) {
            return null;
        }
        return found.userId();
    }

    /** ticket 有效期（秒）；集成测试断言 expiresIn=60 时用 */
    public long ttlSeconds() {
        return ttlMs / 1000;
    }

    /** 当前存活票据数，供测试与排障使用 */
    int ticketCount() {
        return tickets.size();
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        tickets.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
    }

    private record Ticket(Long userId, long expiresAt) {
    }
}
