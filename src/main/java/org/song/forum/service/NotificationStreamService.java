package org.song.forum.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 在线 SSE 连接注册表：userId → 该用户当前所有 SseEmitter。
 *
 * <p>线程安全：Tomcat 的请求线程（register/onCompletion）、心跳线程（@Scheduled）
 * 与业务线程（push，afterCommit 可能就在请求线程里）会并发访问同一个 map，
 * 因此所有结构修改都在 {@code connections} 锁内完成。socket 写入一律不在锁内做：
 * 注册时被淘汰的连接先出锁再 complete，push/心跳则交给有界的发送线程池，
 * 一个慢客户端既占不住全局锁，也阻塞不了业务线程和心跳线程。</p>
 *
 * <p>为什么是内存表：单实例部署（raspi 单容器）够用，且无新依赖；水平扩展时
 * 需要换成 Redis pub/sub 或消息队列，这一限制写进了文档（DEVELOPMENT §6）。</p>
 */
@Slf4j
@Service
public class NotificationStreamService {

    /** 写 socket 是短任务，2 个线程够用；队列只是削峰，满了就丢帧，不拖住调用方 */
    private static final int SEND_THREADS = 2;
    private static final int SEND_QUEUE_CAPACITY = 256;

    private static final AtomicLong SEND_THREAD_INDEX = new AtomicLong();
    private static final AtomicLong REJECTED_FRAMES = new AtomicLong();

    private final long timeoutMs;
    private final int maxConnectionsPerUser;
    /** 发送执行器：生产是有界线程池；测试可注入同步执行器（Runnable::run） */
    private final Executor sendExecutor;

    /** 用 LinkedHashSet 保持注册顺序：超限时淘汰 iterator 的第一个即「最早的一条」 */
    private final Map<Long, Set<SseEmitter>> connections = new HashMap<>();

    @Autowired
    public NotificationStreamService(
            @Value("${app.sse.timeout-ms:1800000}") long timeoutMs,
            @Value("${app.sse.max-connections-per-user:5}") int maxConnectionsPerUser) {
        this(timeoutMs, maxConnectionsPerUser, newSendExecutor());
    }

    /** 供测试注入执行器：同步执行器可复现「发送发生在调用线程」的原行为 */
    NotificationStreamService(long timeoutMs, int maxConnectionsPerUser, Executor sendExecutor) {
        this.timeoutMs = timeoutMs;
        this.maxConnectionsPerUser = Math.max(1, maxConnectionsPerUser);
        this.sendExecutor = sendExecutor;
    }

    /**
     * 注册一条新连接并返回给 Spring MVC。
     * <p>注册后立即发一个注释帧：某些反向代理/容器要等到首字节才认为连接建立，
     * 空等 15 秒会被判成超时；注释帧对 EventSource 不可见，不会触发任何事件。</p>
     */
    public SseEmitter register(Long userId) {
        SseEmitter emitter = createEmitter();
        List<SseEmitter> toClose = new ArrayList<>();
        synchronized (connections) {
            Set<SseEmitter> set = connections.computeIfAbsent(userId, key -> new LinkedHashSet<>());
            while (set.size() >= maxConnectionsPerUser) {
                SseEmitter oldest = set.iterator().next();
                set.remove(oldest);
                toClose.add(oldest);
            }
            set.add(emitter);
        }
        // 出锁后再 complete：complete() 会同步 flush socket，慢客户端不能占着全局锁
        for (SseEmitter oldest : toClose) {
            closeQuietly(userId, oldest);
        }
        if (!toClose.isEmpty()) {
            log.info("用户 {} 的 SSE 连接已达上限 {}，关闭最早的一条", userId, maxConnectionsPerUser);
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
            // 防御性清理：Spring MVC 尚未 initialize 时 send 只会暂存、不会抛；
            // 但真实容器里首帧仍可能因客户端已断开而失败，清掉这条死连接即可。
            remove.run();
        }
        log.info("用户 {} 建立 SSE 连接（当前 {} 条）", userId, connectionCount(userId));
        return emitter;
    }

    /**
     * 向该用户的所有连接推一条通知。序列化交给 Spring 的 Jackson 消息转换器，
     * 与 REST 接口的 JSON 完全同构（前端可以直接 JSON.parse）。
     * <p>写操作异步执行：调用方（业务线程，afterCommit 可能就在请求线程里）
     * 不等待任何客户端，慢连接不会拖慢或回滚业务事务。</p>
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
            // 队列满时只丢这一帧，连接本身不动（可能只是这一瞬间慢）
            execute(new SendTask(() -> doSend(userId, emitter, notification), () -> { }));
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
            Long userId = entry.getKey();
            SseEmitter emitter = entry.getValue();
            // 写操作同样交给发送执行器：单个慢客户端不能拖住整轮心跳；
            // 提交被拒（队列满）说明这条连接已跟不上，直接摘掉。
            execute(new SendTask(() -> doHeartbeat(userId, emitter), () -> remove(userId, emitter)));
        }
    }

    @PreDestroy
    void shutdownSendExecutor() {
        if (sendExecutor instanceof ExecutorService executorService) {
            executorService.shutdownNow();
        }
    }

    /** 当前连接数；测试与日志用 */
    int connectionCount(Long userId) {
        synchronized (connections) {
            Set<SseEmitter> set = connections.get(userId);
            return set == null ? 0 : set.size();
        }
    }

    private void doSend(Long userId, SseEmitter emitter, NotificationResponse notification) {
        try {
            emitter.send(SseEmitter.event()
                    .name("notification")
                    .id(String.valueOf(notification.getId()))
                    .data(notification, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            // 写失败只说明这一条连接断了（浏览器关页/切网），清理它即可；
            // 绝不能把异常抛回业务线程，否则一次推送失败会回滚业务事务。
            // 也不再 completeWithError：send 已失败，容器会通过 onError/onCompletion 收尾，
            // 再调只会让一次正常断连走进全局异常处理，被记成 ERROR/500。
            log.debug("推送通知失败，移除该 SSE 连接: userId={}, notificationId={}",
                    userId, notification.getId());
            remove(userId, emitter);
        }
    }

    private void doHeartbeat(Long userId, SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().comment("heartbeat"));
        } catch (Exception e) {
            // 同 doSend：写失败只移除、不 complete，避免把正常断连升级成错误日志
            log.debug("心跳发送失败，移除该 SSE 连接: userId={}", userId);
            remove(userId, emitter);
        }
    }

    private void execute(SendTask task) {
        try {
            sendExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            // 生产用的拒绝策略是「丢弃并计数」，不会走到这里；注入的执行器可能直接抛
            // （例如已被关闭的线程池），同样丢弃这一帧，不让异常冒回业务/调度线程。
            task.onRejected();
            log.warn("SSE 发送任务被拒绝并丢弃: {}", e.getMessage());
        }
    }

    private void closeQuietly(Long userId, SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("关闭被淘汰的 SSE 连接失败: userId={}", userId, e);
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
     * 一条待发送的帧：把真正的写操作与「被线程池拒绝后的补救」分开，
     * 这样调用方（push/心跳）永远只提交、不阻塞。
     */
    private final class SendTask implements Runnable {

        private final Runnable send;
        private final Runnable onReject;

        SendTask(Runnable send, Runnable onReject) {
            this.send = send;
            this.onReject = onReject;
        }

        @Override
        public void run() {
            send.run();
        }

        /** 队列满被丢弃时执行：心跳顺手摘掉连接，推送只丢当前帧 */
        void onRejected() {
            onReject.run();
        }
    }

    /**
     * 生产用发送线程池：有界队列 + 守护线程。
     * <p>拒绝策略只丢帧并计数，绝不回退到 CallerRunsPolicy——那会让业务线程
     * 重新同步写 socket，正是要避免的阻塞。</p>
     */
    private static ExecutorService newSendExecutor() {
        return new ThreadPoolExecutor(
                SEND_THREADS, SEND_THREADS, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(SEND_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "sse-send-" + SEND_THREAD_INDEX.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                (task, pool) -> {
                    long dropped = REJECTED_FRAMES.incrementAndGet();
                    if (task instanceof SendTask sendTask) {
                        sendTask.onRejected();
                    }
                    log.warn("SSE 发送队列已满（容量 {}），丢弃该帧（累计 {} 次）",
                            SEND_QUEUE_CAPACITY, dropped);
                });
    }

    /**
     * 供测试替换 emitter 工厂：Mockito 的 SseEmitter mock 不会真的初始化 handler，
     * 这样才能在单测里断言 send / complete / completeWithError 的调用。
     */
    protected SseEmitter createEmitter() {
        return new SseEmitter(timeoutMs);
    }
}
