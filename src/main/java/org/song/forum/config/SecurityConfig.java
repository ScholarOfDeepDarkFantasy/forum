package org.song.forum.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import org.song.forum.exception.ErrorCode;
import org.song.forum.exception.ErrorMessages;
import org.song.forum.security.JwtAuthenticationFilter;
import org.song.forum.util.Result;

import java.io.IOException;
import java.util.Locale;

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
    private final ObjectMapper objectMapper;
    private final ErrorMessages errorMessages;

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
     * 正好都是 GET，不加这条规则就会被匿名访问。</p>
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
                        .requestMatchers(HttpMethod.GET, "/api/notifications/stream").permitAll()
                        // CSP 上报由浏览器发起，不带认证头；端点自身用限速/体积上限/永不回显兜底
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login",
                                "/api/csp-report").permitAll()
                        // 必须先于下面的 GET 放行规则声明，否则匿名请求会直达 Controller 抛 500
                        .requestMatchers("/api/auth/me", "/api/auth/profile", "/api/auth/logout").authenticated()
                        .requestMatchers(HttpMethod.GET).permitAll()  // MVP：其余 GET 浏览公开
                        .anyRequest().authenticated()                 // 其余（POST/PUT/DELETE）需登录
                )
                .exceptionHandling(ex -> ex
                        // 未认证访问受保护接口 → 401 JSON（而不是重定向登录页）
                        .authenticationEntryPoint((request, response, e) ->
                                writeError(response, ErrorCode.UNAUTHORIZED, explicitLocale(request)))
                        // 已登录但权限不足 → 403 JSON
                        .accessDeniedHandler((request, response, e) ->
                                writeError(response, ErrorCode.FORBIDDEN, explicitLocale(request)))
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 只认显式的 Accept-Language：没有任何头时返回 null，让 ErrorMessages 归一成中文。
     * 不能直接用 request.getLocale()——它回退到 JVM 默认 locale，生产容器里通常是 en。
     */
    private static Locale explicitLocale(HttpServletRequest request) {
        String acceptLanguage = request.getHeader("Accept-Language");
        return acceptLanguage == null || acceptLanguage.isBlank() ? null : request.getLocale();
    }

    /**
     * Security 过滤器链在 MVC 之外，拿不到 LocaleContextHolder，只能显式传 locale；
     * JSON 交给 ObjectMapper 生成（不手拼字符串），字段与 GlobalExceptionHandler 保持一致。
     */
    private void writeError(HttpServletResponse response, ErrorCode code, Locale locale) throws IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(),
                Result.fail(code, errorMessages.getForLocale(code, locale)));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
