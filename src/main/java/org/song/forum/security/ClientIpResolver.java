package org.song.forum.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 解析登录请求的真实客户端 IP。
 *
 * <p>信任模型：只有来自环回地址（本机 nginx）的请求才读代理头。生产环境
 * {@code deploy/nginx/forum.conf} 的两个 API location 都用
 * {@code proxy_set_header X-Real-IP $remote_addr;} <b>覆盖</b>写入，客户端伪造的同名头不会穿透。
 * 从其他地址直连后端时，代理头一律忽略，直接使用 TCP 对端地址，避免「自己填 X-Real-IP 换一个
 * 桶继续爆破」。</p>
 *
 * <p>只做字面量形状校验（长度 ≤ 45、IPv4 每段 ≤ 255、IPv6 只允许十六进制与冒号），
 * 不调用 {@code InetAddress#getByName}——那会触发 DNS 解析，既慢又给了内网探测面。</p>
 */
@Component
public class ClientIpResolver {

    /** IPv6 文本表示的最大长度（含 IPv4-mapped 形式）。 */
    private static final int MAX_IP_LENGTH = 45;

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){3}$");

    private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:.]+$");

    /**
     * @return 客户端 IP；请求没有可用信息时返回 {@code remoteAddr}（可能是环回地址）
     */
    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (remoteAddr != null && !isLoopback(remoteAddr)) {
            return remoteAddr;
        }

        String realIp = request.getHeader("X-Real-IP");
        if (isIpLiteral(realIp)) {
            return realIp.trim();
        }

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            for (String candidate : forwarded.split(",")) {
                if (isIpLiteral(candidate)) {
                    return candidate.trim();
                }
            }
        }
        return remoteAddr;
    }

    static boolean isLoopback(String ip) {
        return "::1".equals(ip)
                || "0:0:0:0:0:0:0:1".equals(ip)
                || ip.startsWith("127.");
    }

    static boolean isIpLiteral(String candidate) {
        if (candidate == null) {
            return false;
        }
        String value = candidate.trim();
        if (value.isEmpty() || value.length() > MAX_IP_LENGTH) {
            return false;
        }
        // 同时出现点与冒号：要么是 1.2.3.4:80（端口），要么是 IPv4-mapped IPv6，都不接受
        if (value.indexOf(':') >= 0 && value.indexOf('.') >= 0) {
            return false;
        }
        if (IPV4.matcher(value).matches()) {
            for (String part : value.split("\\.")) {
                if (Integer.parseInt(part) > 255) {
                    return false;
                }
            }
            return true;
        }
        return value.indexOf(':') >= 0 && IPV6.matcher(value).matches();
    }
}
