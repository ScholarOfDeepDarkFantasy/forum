package org.song.forum.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.song.forum.dto.notification.NotificationResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 在线 SSE 连接注册表：userId → 该用户当前所有 SseEmitter。
 *
 * <p>线程安全：Tomcat 的请求线程（register/onCompletion）、心跳线程（@Scheduled）
 * 与业务线程（push，afterCommit 可能就在请求线程里）会并发访问同一个 map，
 * 因此所有结构修改都在 {@code connections} 锁内完成；push/心跳先在锁内拷贝快照，
 * 再在锁外 send，避免某个慢客户端把锁占住、拖垮其他用户的推送。</p>
 *
 * <p>为什么是内存表：单实例部署（raspi 单容器）够用，且无新依赖；水平扩展时
 * 需要换成 Redis pub/sub 或消息队列，这一限制写进了文档（DEVELOPMENT §6）。</p>
 */
@Slf4j
@Service
public class NotificationStreamService {

    private final long timeoutMs;
    private final int maxConnectionsPerUser;

    /** 用 LinkedHashSet 保持注册顺序：超限时淘汰 iterator 的第一个即「最早的一条」 */
    private final Map<Long, Set<SseEmitter>> connections = new HashMap<>();

    public NotificationStreamService(
            @Value("${app.sse.timeout-ms:1800000}") long timeoutMs,
            @Value("${app.sse.max-connections-per-user:5}") int maxConnectionsPerUser) {
        this.timeoutMs = timeoutMs;
        this.maxConnectionsPerUser = maxConnectionsPerUser;
    }

    /**
     * 注册一条新连接并返回给 Spring MVC。
     * <p>注册后立即发一个注释帧：某些反向代理/容器要等到首字节才认为连接建立，
     * 空等 15 秒会被判成超时；注释帧对 EventSource 不可见，不会触发任何事件。</p>
     */
    public SseEmitter register(Long userId) {
        SseEmitter emitter = createEmitter();
        synchronized (connections) {
            Set<SseEmitter> set = connections.computeIfAbsent(userId, key -> new LinkedHashSet<>());
            while (set.size() >= maxConnectionsPerUser) {
                SseEmitter oldest = set.iterator().next();
                set.remove(oldest);
                oldest.complete();
                log.info("用户 {} 的 SSE 连接已达上限 {}，关闭最早的一条", userId, maxConnectionsPerUser);
            }
            set.add(emitter);
        }

        Runnable remove = () -> remove(userId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(() -> {
            remove.run();
            emitter.complete();
        });
        emitter.onError(error -> remove.run());

        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (Exception e) {
            // 客户端在注册的一瞬间就断开：直接清理，别把死连接留在表里
            remove.run();
        }
        log.info("用户 {} 建立 SSE 连接（当前 {} 条）", userId, connectionCount(userId));
        return emitter;
    }

    /**
     * 向该用户的所有连接推一条通知。序列化交给 Spring 的 Jackson 消息转换器，
     * 与 REST 接口的 JSON 完全同构（前端可以直接 JSON.parse）。
     */
    public void push(Long userId, NotificationResponse notification) {
        List<SseEmitter> targets;
        synchronized (connections) {
            Set<SseEmitter> set = connections.get(userId);
            if (set == null || set.isEmpty()) {
                return;
            }
            targets = List.copyOf(set);
        }
        for (SseEmitter emitter : targets) {
            try {
                emitter.send(SseEmitter.event()
                        .name("notification")
                        .id(String.valueOf(notification.getId()))
                        .data(notification, MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                // 写失败只说明这一条连接断了（浏览器关页/切网），清理它即可；
                // 绝不能把异常抛回业务线程，否则一次推送失败会回滚业务事务。
                log.debug("推送通知失败，移除该 SSE 连接: userId={}, notificationId={}",
                        userId, notification.getId());
                remove(userId, emitter);
                emitter.completeWithError(e);
            }
        }
    }

    /**
     * 心跳：每 heartbeat-ms 发一个注释帧。注释帧不触发前端事件，只用于：
     * 1) 让 nginx / 中间设备看到流量，不因空闲超时断连；
     * 2) 顺带探活，写失败的连接在这里被清掉。
     */
    @Scheduled(fixedRateString = "${app.sse.heartbeat-ms:15000}")
    public void heartbeat() {
        List<Map.Entry<Long, SseEmitter>> snapshot = new ArrayList<>();
        synchronized (connections) {
            connections.forEach((userId, set) ->
                    set.forEach(emitter -> snapshot.add(Map.entry(userId, emitter))));
        }
        for (Map.Entry<Long, SseEmitter> entry : snapshot) {
            try {
                entry.getValue().send(SseEmitter.event().comment("heartbeat"));
            } catch (Exception e) {
                remove(entry.getKey(), entry.getValue());
            }
        }
    }

    /** 当前连接数；测试与日志用 */
    int connectionCount(Long userId) {
        synchronized (connections) {
            Set<SseEmitter> set = connections.get(userId);
            return set == null ? 0 : set.size();
        }
    }

    private void remove(Long userId, SseEmitter emitter) {
        synchronized (connections) {
            Set<SseEmitter> set = connections.get(userId);
            if (set == null) {
                return;
            }
            set.remove(emitter);
            if (set.isEmpty()) {
                connections.remove(userId);
            }
        }
    }

    /**
     * 供测试替换 emitter 工厂：Mockito 的 SseEmitter mock 不会真的初始化 handler，
     * 这样才能在单测里断言 send / complete / completeWithError 的调用。
     */
    protected SseEmitter createEmitter() {
        return new SseEmitter(timeoutMs);
    }
}
