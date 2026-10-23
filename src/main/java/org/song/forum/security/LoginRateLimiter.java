package org.song.forum.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.song.forum.exception.TooManyLoginAttemptsException;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按客户端 IP 的登录失败限流（单实例内存实现）。
 *
 * <p>算法：窗口内累积失败时间戳；达到 {@code maxFailures} 时把 {@code lockedUntil} 设为
 * now + {@code lockMs} 并清空失败队列；锁定期内所有请求直接 429，<b>不再校验密码</b>，
 * 避免「锁定期间继续试密码」既拖慢响应又继续消耗 BCrypt 算力。成功登录直接删掉该 IP 的记录。</p>
 *
 * <p>为什么不做成滑动窗口计数（只存一个 int）：失败时间戳能同时支撑「窗口过期」与
 * 「锁到期后重新计数」两种判断，且成本只是每 IP 一个小队列。单 IP 队列最多
 * {@code maxFailures} 个元素（达到阈值即清空并锁定），内存上界可控。</p>
 *
 * <p>局限（见 README「已知限制」）：多实例部署时每个实例各算各的，需要换成共享存储。</p>
 */
@Slf4j
@Component
public class LoginRateLimiter {

    private final Clock clock;
    private final int maxFailures;
    private final long windowMs;
    private final long lockMs;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public LoginRateLimiter(Clock clock,
                            @Value("${app.security.login.max-failures:5}") int maxFailures,
                            @Value("${app.security.login.window-ms:900000}") long windowMs,
                            @Value("${app.security.login.lock-ms:900000}") long lockMs) {
        this.clock = clock;
        this.maxFailures = maxFailures;
        this.windowMs = windowMs;
        this.lockMs = lockMs;
    }

    /** 进入登录流程前调用：锁定中直接抛 429 异常，不再校验凭据 */
    public void check(String ip) {
        Window window = windows.get(ip);
        if (window == null) {
            return;
        }
        synchronized (window) {
            long now = clock.millis();
            if (window.lockedUntil > now) {
                long retryAfterSeconds = Math.max(1L, (window.lockedUntil - now + 999) / 1000);
                throw new TooManyLoginAttemptsException(retryAfterSeconds);
            }
        }
    }

    /** 凭据失败后调用：修剪窗口、追加当前时间、必要时锁定 */
    public void recordFailure(String ip) {
        Window window = windows.computeIfAbsent(ip, key -> new Window());
        synchronized (window) {
            long now = clock.millis();
            if (window.lockedUntil > 0 && window.lockedUntil <= now) {
                // 锁已到期：计数归零，重新开始
                window.lockedUntil = 0L;
                window.failures.clear();
            }
            long cutoff = now - windowMs;
            while (!window.failures.isEmpty() && window.failures.peekFirst() <= cutoff) {
                window.failures.pollFirst();
            }
            window.failures.addLast(now);
            if (window.failures.size() >= maxFailures) {
                window.lockedUntil = now + lockMs;
                window.failures.clear();
                log.warn("IP {} 登录失败 {} 次，锁定 {} ms", ip, maxFailures, lockMs);
            }
        }
    }

    /** 登录成功后调用：清空该 IP 的失败记录 */
    public void recordSuccess(String ip) {
        windows.remove(ip);
    }

    /** 定时清理入口：{@code @Scheduled} 只允许 void 方法，逻辑放在可断言返回值的 cleanupExpired() */
    @Scheduled(fixedDelayString = "${app.security.login.cleanup-interval-ms:60000}")
    public void cleanupScheduled() {
        int removed = cleanupExpired();
        if (removed > 0) {
            log.debug("清理登录限流窗口 {} 个，剩余 {}", removed, windows.size());
        }
    }

    /**
     * 清理无锁且已过窗口、或锁已过期的 IP 记录，返回清理条数（供单测断言）。
     */
    public int cleanupExpired() {
        long now = clock.millis();
        int removed = 0;
        Iterator<Map.Entry<String, Window>> iterator = windows.entrySet().iterator();
        while (iterator.hasNext()) {
            Window window = iterator.next().getValue();
            synchronized (window) {
                if (window.lockedUntil > now) {
                    continue;
                }
                Long lastFailure = window.failures.peekLast();
                boolean stale = lastFailure == null || lastFailure <= now - windowMs;
                if (stale) {
                    iterator.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    private static final class Window {

        private final Deque<Long> failures = new ArrayDeque<>();

        private long lockedUntil;
    }
}
