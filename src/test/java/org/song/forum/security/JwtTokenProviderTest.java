package org.song.forum.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JwtTokenProvider 单元测试：签发、解析、版本号 claim、旧 token 兼容。
 * 不启动 Spring 上下文，构造器直接注入测试密钥与 60s 有效期。
 */
class JwtTokenProviderTest {

    private static final String SECRET = "unit-test-secret-key-must-be-at-least-256-bit-long!!";

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider(SECRET, 60_000);
    }

    @Test
    void createAndParse() {
        String token = provider.createToken(1L, "alice", List.of("USER", "ADMIN"), 0L);
        Claims claims = provider.parseToken(token);
        assertThat(claims.getSubject()).isEqualTo("1");
        assertThat(claims.get("username", String.class)).isEqualTo("alice");
    }

    @Test
    void versionClaimSurvivesRoundTrip() {
        String token = provider.createToken(7L, "bob", List.of("USER"), 3L);
        Claims claims = provider.parseToken(token);
        assertThat(JwtTokenProvider.tokenVersion(claims)).isEqualTo(3L);
    }

    @Test
    void legacyTokenWithoutVersionReadsAsZero() {
        // 第七轮之前签发的 token 没有 ver claim；按 spec §5 视为版本 0，不能让老用户集体掉线
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String legacy = Jwts.builder()
                .subject("9")
                .claim("username", "carol")
                .claim("roles", List.of("USER"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key)
                .compact();

        assertThat(JwtTokenProvider.tokenVersion(provider.parseToken(legacy))).isZero();
    }

    @Test
    void invalidTokenReturnsNull() {
        assertThat(provider.validate("not-a-jwt")).isNull();
        assertThat(provider.validate("")).isNull();
    }
}
