package org.song.forum.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 只做一件事：打开 @Scheduled，让 NotificationStreamService 的 SSE 心跳按
 * app.sse.heartbeat-ms 周期执行。
 * <p>放在独立配置类而不是主应用类上，避免以后有人在测试里只加载部分配置
 * （@WebMvcTest 等）时意外启动所有定时任务。</p>
 */
@Configuration
@EnableScheduling
public class SseConfig {
}
