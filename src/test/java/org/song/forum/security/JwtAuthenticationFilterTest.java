package org.song.forum.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.song.forum.entity.UserStatus;
import org.song.forum.repository.UserRepository;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 过滤器单测：签名/解析/会话回查三条路径的四条否定分支各一例。
 * <p>用真实 JwtTokenProvider 造 token（而不是 mock Claims）：mock Claims 要逐方法 stub，
 * Mockito 严格模式会因「某个用例没用到 roles stub」报 UnnecessaryStubbingException；
 * 真实 provider 顺带把签名与解析也纳入覆盖。</p>
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    /** HS256 要求密钥至少 256 bit，这里远超 32 字节 */
    private static final String SECRET = "round8-jwt-filter-test-secret-0123456789abcdefghijklmnopqrstuvwxyz";
    private static final long EXPIRATION_MS = 60_000L;

    @Mock
    private UserRepository userRepository;

    private JwtTokenProvider tokenProvider;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        tokenProvider = new JwtTokenProvider(SECRET, EXPIRATION_MS);
        filter = new JwtAuthenticationFilter(tokenProvider, userRepository);
        ReflectionTestUtils.setField(filter, "headerName", "Authorization");
        ReflectionTestUtils.setField(filter, "prefix", "Bearer ");
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validTokenEstablishesAuthentication() throws Exception {
        String token = tokenProvider.createToken(42L, "alice", List.of("USER"), 0L);
        when(userRepository.findTokenStateById(42L))
                .thenReturn(Optional.of(state(42L, UserStatus.ACTIVE, 0L)));

        filter.doFilter(request(token), new MockHttpServletResponse(), new MockFilterChain());

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getName()).isEqualTo("alice");
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
        assertThat(((ForumUserDetails) authentication.getPrincipal()).getUserId()).isEqualTo(42L);
    }

    @Test
    void noAuthorizationHeaderPassesThroughWithoutAuthentication() throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/api/health"),
                new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(userRepository);
    }

    @Test
    void invalidTokenDoesNotAuthenticate() throws Exception {
        filter.doFilter(request("not-a-jwt"), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(userRepository);
    }

    @Test
    void nonNumericSubjectDoesNotAuthenticate() throws Exception {
        // 手工签发 sub 非数字的 token：validate 能通过（签名有效），但解析 userId 失败
        String token = Jwts.builder()
                .subject("not-a-number")
                .claim("username", "alice")
                .claim("roles", List.of("USER"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + EXPIRATION_MS))
                .signWith(key())
                .compact();

        filter.doFilter(request(token), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        // 顺序保证：subject 解析在查库之前，坏 subject 不应该打库
        verifyNoInteractions(userRepository);
    }

    @Test
    void repositoryFailureIsTreatedAsUnauthenticated() throws Exception {
        String token = tokenProvider.createToken(7L, "bob", List.of("USER"), 0L);
        when(userRepository.findTokenStateById(7L))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatCode(() -> filter.doFilter(request(token), new MockHttpServletResponse(),
                new MockFilterChain()))
                .doesNotThrowAnyException();

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(userRepository).findTokenStateById(7L);
    }

    @Test
    void bannedUserDoesNotAuthenticate() throws Exception {
        String token = tokenProvider.createToken(8L, "carol", List.of("USER"), 0L);
        when(userRepository.findTokenStateById(8L))
                .thenReturn(Optional.of(state(8L, UserStatus.BANNED, 0L)));

        filter.doFilter(request(token), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void versionMismatchDoesNotAuthenticate() throws Exception {
        String token = tokenProvider.createToken(9L, "dave", List.of("USER"), 3L);
        when(userRepository.findTokenStateById(9L))
                .thenReturn(Optional.of(state(9L, UserStatus.ACTIVE, 4L)));

        filter.doFilter(request(token), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // ---------- 辅助 ----------

    private MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    private static UserRepository.UserTokenState state(Long id, UserStatus status, long tokenVersion) {
        return new UserRepository.UserTokenState() {
            @Override
            public Long getId() {
                return id;
            }

            @Override
            public UserStatus getStatus() {
                return status;
            }

            @Override
            public long getTokenVersion() {
                return tokenVersion;
            }
        };
    }
}
