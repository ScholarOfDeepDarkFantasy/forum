package org.song.forum;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;

import java.util.Optional;

/**
 * JPA 审计操作人：MVP 阶段暂不记录操作人，仅使用时间审计
 * （@EnableJpaAuditing 在主启动类上，保证测试切片也能生效）
 */
@Configuration
public class JpaAuditingConfig {

    @Bean
    public AuditorAware<String> auditorProvider() {
        return Optional::empty;
    }
}
