package org.song.forum.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 解析登录请求的真实客户端 IP。
 *
 * <p>信任模型：只有来自<b>环回</b>地址（本机 nginx）或 <b>RFC1918 私网</b>地址的请求才读代理头。
 * 私网这一条是为了兼容 rootless podman 的端口映射：容器内的后端看到的 {@code remoteAddr} 是容器
 * 网关（2026-10-23 实测 {@code 10.89.3.15}，并非 {@code 127.0.0.1}），只认环回就会让全站共用一个
 * 计数桶。生产环境 {@code deploy/nginx/forum.conf} 的两个 API location 都用
 * {@code proxy_set_header X-Real-IP $remote_addr;} <b>覆盖</b>写入，客户端伪造的同名头不会穿透。
 * 从其他（公网）地址直连后端时，代理头一律忽略，直接使用 TCP 对端地址，避免「自己填 X-Real-IP
 * 换一个桶继续爆破」。</p>
 *
 * <p>安全前提与边界：后端端口只发布在宿主机 {@code 127.0.0.1}（见 {@code docker-compose.yml} 的
 * {@code ports}），只有本机 nginx 与 podman 端口映射这两条路径可达，因此本机以外的客户端无法自行
 * 填写 {@code X-Real-IP} 换桶。若将来把 8086 发布到其它网卡或公网，本信任模型必须重新评估——
 * 那时本机任意进程即可伪造该头。</p>
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
        if (remoteAddr != null && !isTrustedProxy(remoteAddr)) {
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

    /**
     * 是否是可以信任其代理头的直连来源：环回（本机 nginx）或 RFC1918 私网
     * （rootless podman 端口映射的容器网关）。
     */
    static boolean isTrustedProxy(String ip) {
        return isLoopback(ip) || isPrivateIpv4(ip);
    }

    /** RFC1918 私网 IPv4：10/8、172.16/12、192.168/16。 */
    static boolean isPrivateIpv4(String ip) {
        if (ip == null) {
            return false;
        }
        String value = ip.trim();
        if (!IPV4.matcher(value).matches()) {
            return false;
        }
        // 正则只保证「1~3 位数字 ×4」，每段范围与私网段在这里判断；非法输入一律返回 false
        String[] parts = value.split("[.]");
        int[] octets = new int[4];
        for (int i = 0; i < octets.length; i++) {
            octets[i] = Integer.parseInt(parts[i]);
            if (octets[i] > 255) {
                return false;
            }
        }
        return octets[0] == 10
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168);
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
