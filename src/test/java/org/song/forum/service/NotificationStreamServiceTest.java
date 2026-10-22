package org.song.forum.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.song.forum.dto.notification.NotificationResponse;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 连接注册表的行为边界：上限淘汰、过期/完成清理、推送隔离、写失败只清理自己。
 * <p>真实 SseEmitter 需要 servlet 容器初始化才能 send，因此用 mock 替换工厂，
 * 只验证「对哪条连接做了什么」，不验证线上字节流（那由集成测试覆盖）。</p>
 * <p>发送执行器注入 {@code Runnable::run} 以保持原有的同步断言；单独用真实线程池
 * 验证 push 不再阻塞调用线程。</p>
 */
class NotificationStreamServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long OTHER_USER_ID = 8L;

    private TestableStreamService service;

    @BeforeEach
    void setUp() {
        service = new TestableStreamService(60_000L, 5);
    }

    private NotificationResponse response(long id) {
        return NotificationResponse.builder()
                .id(id)
                .recipientId(USER_ID)
                .type("LIKE")
                .content("赞了你的帖子")
                .isRead(false)
                .build();
    }

    @Test
    void 注册后计入该用户连接数() {
        SseEmitter emitter = service.nextEmitter();

        SseEmitter returned = service.register(USER_ID);

        assertThat(returned).isSameAs(emitter);
        assertThat(service.connectionCount(USER_ID)).isEqualTo(1);
        assertThat(service.connectionCount(OTHER_USER_ID)).isZero();
    }

    @Test
    void 同一用户超过上限时关闭最早的一条() {
        List<SseEmitter> emitters = service.nextEmitters(6);

        emitters.forEach(emitter -> service.register(USER_ID));

        // 只剩最近 5 条；最早的一条被 complete 关闭
        assertThat(service.connectionCount(USER_ID)).isEqualTo(5);
        verify(emitters.get(0)).complete();
        verify(emitters.get(5), never()).complete();
    }

    @Test
    void 完成回调后从注册表移除() {
        SseEmitter emitter = service.nextEmitter();
        service.register(USER_ID);

        // 模拟 Spring MVC 在连接关闭时回调 onCompletion
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(emitter).onCompletion(captor.capture());
        captor.getValue().run();

        assertThat(service.connectionCount(USER_ID)).isZero();
    }

    @Test
    void 超时回调先移除再complete() {
        SseEmitter emitter = service.nextEmitter();
        service.register(USER_ID);

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(emitter).onTimeout(captor.capture());
        captor.getValue().run();

        assertThat(service.connectionCount(USER_ID)).isZero();
        verify(emitter).complete();
    }

    @Test
    void 错误回调后从注册表移除() {
        SseEmitter emitter = service.nextEmitter();
        service.register(USER_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Throwable>> captor =
                (ArgumentCaptor<Consumer<Throwable>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Consumer.class);
        verify(emitter).onError(captor.capture());
        captor.getValue().accept(new IOException("broken pipe"));

        assertThat(service.connectionCount(USER_ID)).isZero();
    }

    @Test
    void 推送只发给目标用户且发出通知事件() throws IOException {
        SseEmitter target1 = service.nextEmitter();
        SseEmitter target2 = service.nextEmitter();
        SseEmitter other = service.nextEmitter();
        service.register(USER_ID);
        service.register(USER_ID);
        service.register(OTHER_USER_ID);
        // 注册时会各发一个 connected 注释帧，清掉以免干扰 send 计数断言
        clearInvocations(target1, target2, other);

        service.push(USER_ID, response(42L));

        verify(target1, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(target2, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(other, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void 推送事件帧包含事件名_id与JSON数据() throws Exception {
        SseEmitter target = service.nextEmitter();
        service.register(USER_ID);
        clearInvocations(target);

        service.push(USER_ID, response(42L));

        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(target).send(captor.capture());

        Set<ResponseBodyEmitter.DataWithMediaType> parts = captor.getValue().build();
        assertThat(parts).anySatisfy(part ->
                assertThat(part.getMediaType()).isEqualTo(MediaType.APPLICATION_JSON));

        String frame = frameText(parts);
        assertThat(frame)
                .contains("event:notification")
                .contains("id:42")
                .contains("\"type\":\"LIKE\"")
                .contains("\"content\":\"赞了你的帖子\"");
    }

    @Test
    void 推送写失败只移除连接不再completeWithError() throws IOException {
        SseEmitter broken = service.nextEmitter();
        SseEmitter healthy = service.nextEmitter();
        service.register(USER_ID);
        service.register(USER_ID);
        // 注册时会各发一个 connected 注释帧，清掉以免干扰 send 计数断言
        clearInvocations(broken, healthy);
        doThrow(new IOException("broken pipe"))
                .when(broken).send(any(SseEmitter.SseEventBuilder.class));

        service.push(USER_ID, response(43L));

        // send 失败后再 completeWithError 会让断连走进全局异常处理，被记成 500/ERROR
        verify(broken, never()).completeWithError(any());
        assertThat(service.connectionCount(USER_ID)).isEqualTo(1);
        // healthy 在注册阶段发的 connected 帧已被 clearInvocations 清掉，push 只发 1 次
        verify(healthy, times(1)).send(any(SseEmitter.SseEventBuilder.class));

        // 再推一次：坏连接已从注册表摘掉，不会再次尝试写它
        service.push(USER_ID, response(44L));
        verify(broken, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(healthy, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void 心跳向所有连接发送注释帧并清理失败连接() throws IOException {
        SseEmitter alive = service.nextEmitter();
        SseEmitter dead = service.nextEmitter();
        service.register(USER_ID);
        service.register(OTHER_USER_ID);
        clearInvocations(alive, dead);
        doThrow(new IOException("client gone"))
                .when(dead).send(any(SseEmitter.SseEventBuilder.class));

        assertThatCode(() -> service.heartbeat()).doesNotThrowAnyException();

        verify(alive, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        // 失败的 dead 属于 OTHER_USER_ID，被清理；alive 属于 USER_ID，必须保留
        assertThat(service.connectionCount(USER_ID)).isEqualTo(1);
        assertThat(service.connectionCount(OTHER_USER_ID)).isZero();
    }

    @Test
    void 没有连接的用户推送是空操作() {
        assertThatCode(() -> service.push(USER_ID, response(44L))).doesNotThrowAnyException();
    }

    @Test
    void 上限为0时按1处理并仍能注册一条连接() {
        TestableStreamService noLimit = new TestableStreamService(60_000L, 0, Runnable::run);
        noLimit.nextEmitter();

        assertThatCode(() -> noLimit.register(USER_ID)).doesNotThrowAnyException();

        assertThat(noLimit.connectionCount(USER_ID)).isEqualTo(1);
    }

    @Test
    void 推送交给发送执行器异步执行() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, "test-sse-send"));
        try {
            TestableStreamService asyncService = new TestableStreamService(60_000L, 5, executor);
            SseEmitter target = asyncService.nextEmitter();
            asyncService.register(USER_ID);
            clearInvocations(target);

            CountDownLatch sent = new CountDownLatch(1);
            AtomicReference<String> sendThread = new AtomicReference<>();
            doAnswer(invocation -> {
                sendThread.set(Thread.currentThread().getName());
                sent.countDown();
                return null;
            }).when(target).send(any(SseEmitter.SseEventBuilder.class));

            asyncService.push(USER_ID, response(45L));

            // 不用 sleep：发送线程跑完才 countDown，2 秒足够宽松
            assertThat(sent.await(2, TimeUnit.SECONDS))
                    .as("push 应把写操作交给发送执行器，而不是在调用线程里同步写")
                    .isTrue();
            verify(target).send(any(SseEmitter.SseEventBuilder.class));
            assertThat(sendThread.get()).isEqualTo("test-sse-send");
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 把 SseEventBuilder 还原成线上会写出的字节：文本帧（event/id/data 前缀）
     * 原样拼接，JSON 数据用与 Spring 同款的 Jackson 配置序列化。
     */
    private static String frameText(Set<ResponseBodyEmitter.DataWithMediaType> parts) throws Exception {
        ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();
        StringBuilder frame = new StringBuilder();
        for (ResponseBodyEmitter.DataWithMediaType part : parts) {
            Object data = part.getData();
            if (data instanceof CharSequence text) {
                frame.append(text);
            } else {
                frame.append(mapper.writeValueAsString(data));
            }
        }
        return frame.toString();
    }

    /** 测试替身：createEmitter 从队列取 mock，并记录顺序供断言 */
    static class TestableStreamService extends NotificationStreamService {

        private final Deque<SseEmitter> pending = new ArrayDeque<>();

        TestableStreamService(long timeoutMs, int maxConnectionsPerUser) {
            this(timeoutMs, maxConnectionsPerUser, Runnable::run);
        }

        TestableStreamService(long timeoutMs, int maxConnectionsPerUser, Executor sendExecutor) {
            super(timeoutMs, maxConnectionsPerUser, sendExecutor);
        }

        SseEmitter nextEmitter() {
            SseEmitter emitter = mock(SseEmitter.class);
            pending.add(emitter);
            return emitter;
        }

        List<SseEmitter> nextEmitters(int count) {
            return java.util.stream.IntStream.range(0, count)
                    .mapToObj(index -> nextEmitter())
                    .toList();
        }

        @Override
        protected SseEmitter createEmitter() {
            return pending.poll();
        }
    }
}
