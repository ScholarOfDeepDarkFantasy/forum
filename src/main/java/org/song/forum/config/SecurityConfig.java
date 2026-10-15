package org.song.forum.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
 * <p>说明：按确认结论采用 JWT 无状态认证，因此禁用 Session、CSRF 与表单登录。</p>
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /** 公开接口：认证文档健康检查、注册登录、板块与内容浏览 */
    private static final String[] PUBLIC_GET = {
            "/api/health",
            "/api/boards/**",
            "/api/posts/**",
            "/api/auth/users/*",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
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
                        .requestMatchers(PUBLIC_GET).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        // 必须先于下面的 GET 放行规则声明，否则匿名请求会直达 Controller 抛 500
                        .requestMatchers("/api/auth/me", "/api/auth/profile").authenticated()
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
