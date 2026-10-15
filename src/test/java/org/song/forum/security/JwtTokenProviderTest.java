package org.song.forum.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JWT 工具单测：签发 → 解析 → 过期校验
 */
class JwtTokenProviderTest {

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        // 32 字节以上测试密钥
        provider = new JwtTokenProvider(
                "unit-test-secret-key-must-be-at-least-256-bit-long!!", 60_000);
    }

    @Test
    void createAndParse() {
        String token = provider.createToken(1L, "alice", List.of("USER", "ADMIN"));
        Claims claims = provider.validate(token);
        assertThat(claims).isNotNull();
        assertThat(claims.getSubject()).isEqualTo("1");
        assertThat(claims.get("username", String.class)).isEqualTo("alice");
    }

    @Test
    void invalidTokenReturnsNull() {
        assertThat(provider.validate("not-a-jwt")).isNull();
        assertThat(provider.validate("")).isNull();
    }
}
