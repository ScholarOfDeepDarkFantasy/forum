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

    /** 是否已登录 */
    public static boolean isAuthenticated() {
        return currentUser() != null;
    }
}
