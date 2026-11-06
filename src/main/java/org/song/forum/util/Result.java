package org.song.forum.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.song.forum.exception.ErrorCode;

/**
 * 统一响应结构 Result&lt;T&gt;
 * <p>success=true 时 data 为业务数据；success=false 时 code 为机器可读错误码、
 * message 为可展示文案（按请求语言本地化），data 为 null。</p>
 * <p>code 在成功响应里为 null，由 {@code NON_NULL} 省略，成功响应结构与第十四轮一致。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> {

    private boolean success;
    private String code;
    private String message;
    private T data;
    private Long timestamp;

    public static <T> Result<T> ok(T data) {
        return new Result<>(true, null, "ok", data, System.currentTimeMillis());
    }

    public static <T> Result<T> ok(T data, String message) {
        return new Result<>(true, null, message, data, System.currentTimeMillis());
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(String message) {
        return new Result<>(false, null, message, null, System.currentTimeMillis());
    }

    public static <T> Result<T> fail(String message, T data) {
        return new Result<>(false, null, message, data, System.currentTimeMillis());
    }

    /** 失败响应（带机器可读错误码）：message 缺省为 code 名，仅作非 Web 场景兜底。 */
    public static <T> Result<T> fail(ErrorCode code) {
        return fail(code, code.name());
    }

    public static <T> Result<T> fail(ErrorCode code, String message) {
        return new Result<>(false, code.name(), message, null, System.currentTimeMillis());
    }

    public static <T> Result<T> fail(ErrorCode code, String message, T data) {
        return new Result<>(false, code.name(), message, data, System.currentTimeMillis());
    }
}
