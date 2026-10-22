package org.song.forum.exception;

/**
 * SSE 订阅凭证（ticket）缺失、无效、过期或已被使用。
 * <p>单独建异常而不是复用 BusinessException：后者会被映射成 400，
 * 而前端/nginx 冒烟都依赖「无票或废票必须是 401」这一语义。</p>
 */
public class InvalidStreamTicketException extends RuntimeException {

    public InvalidStreamTicketException(String message) {
        super(message);
    }
}
