package org.song.forum.exception;

/**
 * 登录失败次数超限（HTTP 429）。
 * <p>携带 {@code retryAfterSeconds}，由 GlobalExceptionHandler 写入 {@code Retry-After} 响应头；
 * 文案按「向上取整的分钟数、至少 1 分钟」生成，避免出现「请 0 分钟后再试」。</p>
 */
public class TooManyLoginAttemptsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyLoginAttemptsException(long retryAfterSeconds) {
        super(message(retryAfterSeconds));
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    private static String message(long retryAfterSeconds) {
        long minutes = Math.max(1L, (retryAfterSeconds + 59) / 60);
        return "登录失败次数过多，请 " + minutes + " 分钟后再试";
    }
}
