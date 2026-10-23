package org.song.forum.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 客户端 IP 解析：只信环回跳数进来的 X-Real-IP；非环回请求一律用 remoteAddr，
 * 防止客户端自己塞一个 X-Real-IP 就绕过限流。
 */
class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver();

    @Test
    void nonLoopbackRemoteAddrWinsAndProxyHeadersAreIgnored() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.20.30.40");
        request.addHeader("X-Real-IP", "203.0.113.7");
        request.addHeader("X-Forwarded-For", "198.51.100.1");

        assertThat(resolver.resolve(request)).isEqualTo("10.20.30.40");
    }

    @Test
    void loopbackRequestUsesRealIp() {
        MockHttpServletRequest request = loopback();
        request.addHeader("X-Real-IP", "203.0.113.7");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void invalidRealIpFallsBackToFirstValidForwardedForEntry() {
        MockHttpServletRequest request = loopback();
        request.addHeader("X-Real-IP", "not-an-ip");
        request.addHeader("X-Forwarded-For", "unknown, 198.51.100.9, 203.0.113.1");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.9");
    }

    @Test
    void forwardedForSkipsOutOfRangeEntries() {
        MockHttpServletRequest request = loopback();
        request.addHeader("X-Forwarded-For", "300.1.1.1, 198.51.100.9");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.9");
    }

    @Test
    void loopbackWithoutProxyHeadersFallsBackToRemoteAddr() {
        MockHttpServletRequest request = loopback();

        assertThat(resolver.resolve(request)).isEqualTo("127.0.0.1");
    }

    @Test
    void acceptsIpv6LiteralButRejectsPortSuffix() {
        MockHttpServletRequest ipv6 = loopback();
        ipv6.addHeader("X-Real-IP", "2001:db8::1");
        assertThat(resolver.resolve(ipv6)).isEqualTo("2001:db8::1");

        MockHttpServletRequest withPort = loopback();
        withPort.addHeader("X-Real-IP", "203.0.113.7:5678");
        assertThat(resolver.resolve(withPort)).isEqualTo("127.0.0.1");
    }

    private MockHttpServletRequest loopback() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
