package org.song.forum.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应结构 Result&lt;T&gt;
 * <p>success=true 时 data 为业务数据；success=false 时 message 携带错误信息，data 为 null。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> {

    private boolean success;
    private String message;
    private T data;
    private Long timestamp;

    public static <T> Result<T> ok(T data) {
        return new Result<>(true, "ok", data, System.currentTimeMillis());
    }

    public static <T> Result<T> ok(T data, String message) {
        return new Result<>(true, message, data, System.currentTimeMillis());
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(String message) {
        return new Result<>(false, message, null, System.currentTimeMillis());
    }

    public static <T> Result<T> fail(String message, T data) {
        return new Result<>(false, message, data, System.currentTimeMillis());
    }
}
