package org.song.forum.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.song.forum.exception.TooManyLoginAttemptsException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 限流器纯单元测试：窗口累积、阈值锁定、锁定到期、成功清零、窗口过期、清理、IP 隔离。
 */
class LoginRateLimiterTest {

    private static final int MAX_FAILURES = 3;
    private static final long WINDOW_MS = 60_000L;
    private static final long LOCK_MS = 60_000L;
    private static final String IP = "203.0.113.5";

    private MutableClock clock;
    private LoginRateLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(1_700_000_000_000L);
        limiter = new LoginRateLimiter(clock, MAX_FAILURES, WINDOW_MS, LOCK_MS);
    }

    @Test
    void freshIpIsNotBlocked() {
        assertThatCode(() -> limiter.check(IP)).doesNotThrowAnyException();
    }

    @Test
    void failuresBelowThresholdDoNotLock() {
        limiter.recordFailure(IP);
        limiter.recordFailure(IP);

        assertThatCode(() -> limiter.check(IP)).doesNotThrowAnyException();
    }

    @Test
    void reachingThresholdLocksForTheWholeLockWindow() {
        failTimes(MAX_FAILURES);

        assertThatThrownBy(() -> limiter.check(IP))
                .isInstanceOf(TooManyLoginAttemptsException.class)
                .hasMessage("登录失败次数过多，请 1 分钟后再试");
        assertThat(retryAfterSeconds()).isEqualTo(60L);
    }

    @Test
    void successClearsPreviousFailures() {
        limiter.recordFailure(IP);
        limiter.recordFailure(IP);
        limiter.recordSuccess(IP);
        limiter.recordFailure(IP);
        limiter.recordFailure(IP);

        assertThatCode(() -> limiter.check(IP)).doesNotThrowAnyException();
    }

    @Test
    void failuresOutsideWindowDoNotAccumulate() {
        limiter.recordFailure(IP);
        clock.advance(WINDOW_MS + 1);
        limiter.recordFailure(IP);
        clock.advance(WINDOW_MS + 1);
        limiter.recordFailure(IP);

        assertThatCode(() -> limiter.check(IP)).doesNotThrowAnyException();
    }

    @Test
    void lockExpiresAfterLockWindowAndCounterResets() {
        failTimes(MAX_FAILURES);

        clock.advance(LOCK_MS + 1);

        assertThatCode(() -> limiter.check(IP)).doesNotThrowAnyException();

        limiter.recordFailure(IP);
        limiter.recordFailure(IP);
        assertThatCode(() -> limiter.check(IP)).doesNotThrowAnyException();
    }

    @Test
    void retryAfterSecondsRoundsUpAndIsAtLeastOne() {
        failTimes(MAX_FAILURES);

        clock.advance(LOCK_MS - 500);

        assertThat(retryAfterSeconds()).isEqualTo(1L);
    }

    @Test
    void lockedIpDoesNotAffectOtherIps() {
        failTimes(MAX_FAILURES);

        assertThatThrownBy(() -> limiter.check(IP)).isInstanceOf(TooManyLoginAttemptsException.class);
        assertThatCode(() -> limiter.check("198.51.100.77")).doesNotThrowAnyException();
    }

    @Test
    void cleanupKeepsLockedWindowsAndRemovesStaleOnes() {
        failTimes(MAX_FAILURES);                 // IP 进入锁定
        clock.advance(LOCK_MS / 2);              // 锁未到期
        limiter.recordFailure("198.51.100.3");   // 另一个 IP 一次失败

        assertThat(limiter.cleanupExpired()).isZero();

        clock.advance(LOCK_MS + 1);              // 锁过期，且另一个窗口的失败也超出窗口

        assertThat(limiter.cleanupExpired()).isEqualTo(2);
    }

    // ---------- 辅助 ----------

    private void failTimes(int times) {
        for (int i = 0; i < times; i++) {
            limiter.recordFailure(IP);
        }
    }

    private long retryAfterSeconds() {
        try {
            limiter.check(IP);
        } catch (TooManyLoginAttemptsException e) {
            return e.getRetryAfterSeconds();
        }
        throw new AssertionError("预期该 IP 已被限流，但 check 未抛异常");
    }

    /** 可手动推进的 Clock，避免测试里 sleep。 */
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
