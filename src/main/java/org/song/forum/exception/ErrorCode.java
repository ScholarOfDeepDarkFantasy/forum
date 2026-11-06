package org.song.forum.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 机器可读错误码：常量名即响应体 {@code code}，同时携带 HTTP 状态与文案键。
 *
 * <p>新增错误码时必须同步三处：①`messages.properties`；②`messages_en.properties`；
 * ③前端 `src/api/index.js` 的 `CODE_MESSAGES` 兜底表。中文文案必须与既有响应逐字一致，
 * 否则前端提示与 E2E 断言会回归。</p>
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    BAD_JSON(HttpStatus.BAD_REQUEST, "error.bad-json"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "error.unsupported-media-type"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "error.method-not-allowed"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "error.validation-failed"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "error.unauthorized"),
    BAD_CREDENTIALS(HttpStatus.UNAUTHORIZED, "error.bad-credentials"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "error.forbidden"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "error.not-found"),
    UPLOAD_TOO_LARGE(HttpStatus.BAD_REQUEST, "error.upload-too-large"),
    MISSING_PART(HttpStatus.BAD_REQUEST, "error.missing-part"),
    MULTIPART_REQUIRED(HttpStatus.BAD_REQUEST, "error.multipart-required"),
    MISSING_PARAMETER(HttpStatus.BAD_REQUEST, "error.missing-parameter"),
    TYPE_MISMATCH(HttpStatus.BAD_REQUEST, "error.type-mismatch"),
    STREAM_TICKET_INVALID(HttpStatus.UNAUTHORIZED, "error.stream-ticket-invalid"),
    TOO_MANY_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS, "error.too-many-attempts"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "error.internal"),
    BUSINESS_ERROR(HttpStatus.BAD_REQUEST, "error.business");

    private final HttpStatus httpStatus;
    private final String messageKey;
}
