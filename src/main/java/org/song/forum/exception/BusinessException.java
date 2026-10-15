package org.song.forum.exception;

/**
 * 业务异常：Service 层校验失败时抛出，由 GlobalExceptionHandler 统一转换为 Result
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
    }
}
