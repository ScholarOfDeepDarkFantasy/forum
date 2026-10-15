package org.song.forum.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA 审计配置：配合实体中的 @CreatedDate / @LastModifiedDate
 * 自动填充 create_time / update_time
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {

    @Bean
    public org.springframework.data.domain.AuditorAware<String> auditorProvider() {
        // MVP 阶段暂不记录操作人，仅使用时间审计；第 3 阶段接入 Security 后可返回当前用户名
        return new org.springframework.data.domain.AuditorAware<String>() {
            @Override
            public java.util.Optional<String> getCurrentAuditor() {
                return java.util.Optional.empty();
            }
        };
    }
}
