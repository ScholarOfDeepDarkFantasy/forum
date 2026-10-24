package org.song.forum.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.song.forum.entity.UserStatus;
import org.song.forum.repository.UserRepository;
import org.song.forum.util.JwtAuthUtil;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器：从 Authorization: Bearer &lt;token&gt; 恢复认证态。
 * <p>第七轮起，签名有效不再等于会话有效：每个请求都要回查用户当前状态与 token 版本，
 * 封禁（status=BANNED）或登出/封禁导致的版本 +1 都会让旧 token 当场失效。
 * 不建立认证态后由 SecurityConfig 的 entry point 统一返回 401，不在这里写响应体。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final UserRepository userRepository;

    @Value("${jwt.header:Authorization}")
    private String headerName;

    @Value("${jwt.prefix:Bearer }")
    private String prefix;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(headerName);
        if (StringUtils.hasText(header) && header.startsWith(prefix)) {
            String token = header.substring(prefix.length()).trim();
            Claims claims = tokenProvider.validate(token);
            if (claims != null
                    && SecurityContextHolder.getContext().getAuthentication() == null
                    && isSessionAlive(claims)) {
                Long userId = Long.valueOf(claims.getSubject());
                String username = claims.get("username", String.class);
                List<String> roles = JwtAuthUtil.claimRoles(claims);
                ForumUserDetails principal = ForumUserDetails.fromClaims(userId, username, roles);
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 会话是否仍然有效：用户存在、未被封禁、且 token 版本与库中一致。
     * <p>旧 token 没有 ver claim 时 {@link JwtTokenProvider#tokenVersion(Claims)} 返回 0，
     * 因此版本为 0 的老用户不会被强制重登；一旦版本变成 1，旧 token 立即被拒。</p>
     * <p>数据库不可用时按<b>未认证</b>处理（fail-closed）：受保护接口由 entry point 返回 401，
     * 公开 GET 仍匿名可用。这里不修改任何持久化状态，因此库恢复后同一 token 依然有效——
     * 与「登出会让 token 永久失效」是两回事。</p>
     */
    private boolean isSessionAlive(Claims claims) {
        Long userId;
        try {
            userId = Long.valueOf(claims.getSubject());
        } catch (NumberFormatException e) {
            return false;
        }
        try {
            return userRepository.findTokenStateById(userId)
                    .filter(state -> state.getStatus() != UserStatus.BANNED)
                    .map(state -> state.getTokenVersion() == JwtTokenProvider.tokenVersion(claims))
                    .orElse(false);
        } catch (DataAccessException e) {
            // 只吞数据访问异常：NPE 之类的编程错误仍应冒出来，不能被伪装成「未登录」。
            // 用 debug 而不是 warn：库故障期间每请求一条 WARN 会刷屏，真正的失败在数据源层已有日志。
            log.debug("回查会话状态失败，按未认证处理: {}", e.toString());
            return false;
        }
    }
}
