package org.song.forum.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录用户存取工具：Controller/Service 获取当前用户 ID
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /** 返回当前 ForumUserDetails；未登录返回 null */
    public static ForumUserDetails currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof ForumUserDetails details) {
            return details;
        }
        return null;
    }

    /** 当前用户 ID；未登录抛出 IllegalStateException（由全局异常处理兜底） */
    public static Long currentUserId() {
        ForumUserDetails details = currentUser();
        if (details == null) {
            throw new IllegalStateException("未登录");
        }
        return details.getUserId();
    }

    /**
     * 当前用户 ID；未登录返回 null（不抛异常）。
     * 供公共可匿名访问的接口使用（如帖子列表/详情需要读取"当前用户是否已点赞"），
     * 避免在 permitAll 路径上因缺少认证而中断请求。
     */
    public static Long currentUserIdOrNull() {
        ForumUserDetails details = currentUser();
        return details == null ? null : details.getUserId();
    }

    /** 是否已登录 */
    public static boolean isAuthenticated() {
        return currentUser() != null;
    }
}
