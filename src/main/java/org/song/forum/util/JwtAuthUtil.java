package org.song.forum.util;

import io.jsonwebtoken.Claims;

import java.util.List;
import java.util.Map;

/**
 * JWT Claims 读取辅助：roles claim 可能被 Jackson 反序列化为 List<Map>，需安全转换
 */
public final class JwtAuthUtil {

    private JwtAuthUtil() {
    }

    @SuppressWarnings("unchecked")
    public static List<String> claimRoles(Claims claims) {
        Object raw = claims.get("roles");
        if (raw instanceof List<?> list) {
            return list.stream()
                    .map(item -> {
                        if (item instanceof String s) return s;
                        if (item instanceof Map<?, ?> m && m.get("authority") != null) {
                            return String.valueOf(m.get("authority"));
                        }
                        return String.valueOf(item);
                    })
                    .toList();
        }
        return List.of();
    }
}
