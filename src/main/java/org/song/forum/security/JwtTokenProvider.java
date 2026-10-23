package org.song.forum.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * JWT 工具（jjwt 0.13.0）：签发 / 解析 / 校验
 * HS256 对称密钥来自 application.yml 的 jwt.secret（生产用环境变量 JWT_SECRET 覆盖）
 */
@Component
public class JwtTokenProvider {

    private final SecretKey key;
    private final long expirationMs;

    public JwtTokenProvider(@Value("${jwt.secret}") String secret,
                            @Value("${jwt.expiration-ms}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    /** 签发 Token：subject=用户ID，claims 携带用户名、角色与 token 版本号 */
    public String createToken(Long userId, String username, List<String> roles, long tokenVersion) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("roles", roles)
                .claim("ver", tokenVersion)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    public long getExpirationMs() {
        return expirationMs;
    }

    /**
     * 读取 token 里的版本号。
     * <p>第七轮之前签发的 token 没有 {@code ver} claim，按 0 处理；jjwt 反序列化小整数给的是
     * {@link Integer}，因此统一按 {@link Number} 取 long。</p>
     */
    public static long tokenVersion(Claims claims) {
        Object raw = claims.get("ver");
        return raw instanceof Number number ? number.longValue() : 0L;
    }

    /** 解析全部 Claims；无效或过期抛 JwtException */
    public Claims parseToken(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    /** 校验签名与有效期，返回 Claims；失败返回 null（ExpiredJwtException 是 JwtException 子类，无需重复列出） */
    public Claims validate(String token) {
        try {
            return parseToken(token);
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
