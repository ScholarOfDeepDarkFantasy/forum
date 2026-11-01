package org.song.forum.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 按客户端 IP 的 CSP 上报限速（固定窗口，单实例内存实现）。
 *
 * <p>窗口内每个 IP 最多放行 {@code maxPerMinute} 条，超过的静默丢弃。限速在读取和解析
 * 请求体<b>之前</b>执行，避免被刷量时把 CPU 花在 JSON 解析上。</p>
 *
 * <p>用 {@link ConcurrentHashMap#compute} 让「判断窗口是否过期 + 计数」成为一个原子操作；
 * 清理时按 key + 实例身份删除，避免误删刚被其他线程换上的新窗口。</p>
 *
 * <p>局限（见设计 §7）：多实例部署各算各的、重启清零；攻击者换 IP 仍可放大日志量，
 * 但每个 IP 有硬上限。</p>
 */
@Slf4j
@Component
public class CspReportThrottle {

    private final Clock clock;
    private final int maxPerMinute;
    private final long windowMs;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public CspReportThrottle(Clock clock,
                             @Value("${app.security.csp-report.max-per-minute:20}") int maxPerMinute,
                             @Value("${app.security.csp-report.window-ms:60000}") long windowMs) {
        // 配置写错时启动即失败，而不是在生产表现为「限流悄悄失效」
        if (maxPerMinute < 1) {
            throw new IllegalArgumentException(
                    "app.security.csp-report.max-per-minute 必须 >= 1，当前 " + maxPerMinute);
        }
        if (windowMs <= 0) {
            throw new IllegalArgumentException(
                    "app.security.csp-report.window-ms 必须 > 0，当前 " + windowMs);
        }
        this.clock = clock;
        this.maxPerMinute = maxPerMinute;
        this.windowMs = windowMs;
    }

    /**
     * 尝试为本次上报占用一个名额。
     *
     * @return {@code true} 放行；{@code false} 本窗口内已超过上限，调用方应静默丢弃
     */
    public boolean tryAcquire(String ip) {
        long now = clock.millis();
        AtomicBoolean allowed = new AtomicBoolean(false);
        windows.compute(ip, (key, window) -> {
            if (window == null || isExpired(window, now)) {
                allowed.set(true);
                return new Window(now);
            }
            if (window.count < maxPerMinute) {
                window.count++;
                allowed.set(true);
            }
            return window;
        });
        return allowed.get();
    }

    /** 定时清理入口：{@code @Scheduled} 只允许 void 方法，逻辑放在可断言返回值的 cleanupExpired()。 */
    @Scheduled(fixedDelayString = "${app.security.csp-report.cleanup-interval-ms:60000}")
    public void cleanupScheduled() {
        int removed = cleanupExpired();
        if (removed > 0) {
            log.debug("清理 CSP 上报限流窗口 {} 个，剩余 {}", removed, windows.size());
        }
    }

    /** 清理已过窗口的 IP 记录，返回清理条数（供单测断言）。 */
    public int cleanupExpired() {
        long now = clock.millis();
        int removed = 0;
        for (Map.Entry<String, Window> entry : windows.entrySet()) {
            // 按 key + 实例身份删除：并发下该条目可能已被换成新窗口
            if (isExpired(entry.getValue(), now) && windows.remove(entry.getKey(), entry.getValue())) {
                removed++;
            }
        }
        return removed;
    }

    private boolean isExpired(Window window, long now) {
        return now - window.windowStart >= windowMs;
    }

    private static final class Window {

        private final long windowStart;
        private int count;

        private Window(long windowStart) {
            this.windowStart = windowStart;
            this.count = 1;
        }
    }
}
