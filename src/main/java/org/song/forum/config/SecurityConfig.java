package org.song.forum.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.song.forum.security.JwtAuthenticationFilter;

/**
 * Spring Security 6 配置（无状态 JWT 方案）
 * <p>说明：按确认结论采用 JWT 无状态认证，因此禁用 Session、CSRF 与表单登录。
 * {@code @EnableMethodSecurity} 启用方法级 @PreAuthorize 注解（管理员/版主权限）。</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /** 公开接口：认证文档健康检查、注册登录、板块与内容浏览 */
    private static final String[] PUBLIC_GET = {
            "/api/health",
            "/api/boards/**",
            "/api/posts/**",
            "/api/tags/**",
            "/api/auth/users/*",
            "/uploads/avatars/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    /**
     * 管理员专属路径：显式写在访问规则最前面。
     * <p>为什么必须单列：下面的 {@code requestMatchers(HttpMethod.GET).permitAll()} 会放行
     * 任意 GET 请求，actuator 端点（{@code /actuator/health}、{@code /actuator/info} ...）
     * 正好都是 GET，不加这条规则就会被匿名访问。宁可显式拒绝，也不要依赖"当前只暴露了 health"
     * 这种运行时配置——配置改了，权限就会悄悄松开。</p>
     */
    private static final String[] ADMIN_ONLY = {
            "/actuator/**"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())                       // JWT 无状态方案，无 Cookie 会话
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .authorizeHttpRequests(auth -> auth
                        // 必须最先声明：否则下面 GET 全放行会把 /actuator 一并放开
                        .requestMatchers(ADMIN_ONLY).hasRole("ADMIN")
                        .requestMatchers(PUBLIC_GET).permitAll()
                        // SSE 订阅：EventSource 不能带 Authorization 头，鉴权由一次性 ticket 承担。
                        // 必须显式写出并说明原因：收紧「GET 全放行」时不能靠记忆判断它是否被误伤。
                        .requestMatchers(HttpMethod.GET, "/api/notifications/stream").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        // 必须先于下面的 GET 放行规则声明，否则匿名请求会直达 Controller 抛 500
                        .requestMatchers("/api/auth/me", "/api/auth/profile", "/api/auth/logout").authenticated()
                        .requestMatchers(HttpMethod.GET).permitAll()  // MVP：其余 GET 浏览公开
                        .anyRequest().authenticated()                 // 其余（POST/PUT/DELETE）需登录
                )
                .exceptionHandling(ex -> ex
                        // 未认证访问受保护接口 → 401 JSON（而不是重定向登录页）
                        .authenticationEntryPoint((request, response, e) -> {
                            response.setStatus(401);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write(
                                    "{\"success\":false,\"message\":\"未登录或登录已过期\"}");
                        })
                        // 已登录但权限不足 → 403 JSON
                        .accessDeniedHandler((request, response, e) -> {
                            response.setStatus(403);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write(
                                    "{\"success\":false,\"message\":\"无权限执行此操作\"}");
                        })
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
