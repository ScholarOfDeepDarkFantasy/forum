package org.song.forum.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CSP 上报限速器纯单元测试：上限放行、窗口滚动、IP 隔离。
 * <p>用可推进的 Clock，避免测试里 sleep。</p>
 */
class CspReportThrottleTest {

    private static final int MAX_PER_MINUTE = 3;
    private static final long WINDOW_MS = 60_000L;
    private static final String IP = "203.0.113.20";

    private MutableClock clock;
    private CspReportThrottle throttle;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(1_700_000_000_000L);
        throttle = new CspReportThrottle(clock, MAX_PER_MINUTE, WINDOW_MS);
    }

    @Test
    void allowsUpToLimitThenRejectsWithinSameWindow() {
        assertThat(throttle.tryAcquire(IP)).isTrue();
        assertThat(throttle.tryAcquire(IP)).isTrue();
        assertThat(throttle.tryAcquire(IP)).isTrue();
        assertThat(throttle.tryAcquire(IP)).isFalse();
        assertThat(throttle.tryAcquire(IP)).isFalse();
    }

    @Test
    void windowRolloverResetsCounter() {
        for (int i = 0; i < MAX_PER_MINUTE; i++) {
            assertThat(throttle.tryAcquire(IP)).isTrue();
        }
        assertThat(throttle.tryAcquire(IP)).isFalse();

        clock.advance(WINDOW_MS);

        assertThat(throttle.tryAcquire(IP)).isTrue();
        assertThat(throttle.tryAcquire(IP)).isTrue();
        assertThat(throttle.tryAcquire(IP)).isTrue();
        assertThat(throttle.tryAcquire(IP)).isFalse();
    }

    @Test
    void differentIpsAreCountedSeparately() {
        for (int i = 0; i < MAX_PER_MINUTE; i++) {
            throttle.tryAcquire(IP);
        }
        assertThat(throttle.tryAcquire(IP)).isFalse();

        assertThat(throttle.tryAcquire("198.51.100.21")).isTrue();
        assertThat(throttle.tryAcquire("198.51.100.22")).isTrue();
    }

    // ---------- 辅助 ----------

    /** 可手动推进的 Clock。 */
    private static final class MutableClock extends Clock {

        private long millis;

        private MutableClock(long millis) {
            this.millis = millis;
        }

        private void advance(long deltaMs) {
            this.millis += deltaMs;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }
}
