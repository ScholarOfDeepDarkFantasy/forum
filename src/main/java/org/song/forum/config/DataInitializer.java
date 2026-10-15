package org.song.forum.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.song.forum.entity.User;
import org.song.forum.entity.UserStatus;
import org.song.forum.repository.RoleRepository;
import org.song.forum.repository.UserRepository;

import java.util.Set;

/**
 * 管理员账号种子：应用启动时若不存在 admin 用户则创建（ADMIN 角色 + 密码 admin123456）。
 * 仅在用户表为空或无 admin 用户时执行，幂等可重复启动。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        userRepository.findByUsername("admin").ifPresentOrElse(
                u -> { /* 已存在，跳过 */ },
                () -> {
                    User admin = User.builder()
                            .username("admin")
                            .password(passwordEncoder.encode("admin123456"))
                            .email("admin@javaforum.local")
                            .nickname("管理员")
                            .status(UserStatus.ACTIVE)
                            .build();
                    admin.getRoles().add(roleRepository.findByName("ADMIN")
                            .orElseThrow(() -> new IllegalStateException("系统缺少 ADMIN 角色，请检查 Flyway 种子数据")));
                    admin.getRoles().add(roleRepository.findByName("MODERATOR")
                            .orElseThrow(() -> new IllegalStateException("系统缺少 MODERATOR 角色，请检查 Flyway 种子数据")));
                    admin.getRoles().add(roleRepository.findByName("USER")
                            .orElseThrow(() -> new IllegalStateException("系统缺少 USER 角色，请检查 Flyway 种子数据")));
                    userRepository.save(admin);
                    log.info("已创建管理员账号 admin（默认密码 admin123456，请在生产环境立即修改）");
                });
    }
}
