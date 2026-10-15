package org.song.forum;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JavaForum 主启动类
 * <p>@EnableJpaAuditing 放在主类上，确保 @DataJpaTest 等测试切片也能加载审计配置</p>
 */
@SpringBootApplication
@EnableJpaAuditing
public class ForumApplication {

    public static void main(String[] args) {
        SpringApplication.run(ForumApplication.class, args);
    }
}
