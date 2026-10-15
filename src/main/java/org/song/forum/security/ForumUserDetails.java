package org.song.forum.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.song.forum.entity.User;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

/**
 * Spring Security UserDetails 实现：包装论坛 User 实体
 * 用户状态（封禁/禁言）在 AuthService 登录时校验，这里只承载凭证与角色
 */
public class ForumUserDetails implements UserDetails, Serializable {

    private final Long userId;
    private final String username;
    private final String password;
    private final Collection<? extends GrantedAuthority> authorities;

    public ForumUserDetails(Long userId, String username, String password,
                            Collection<? extends GrantedAuthority> authorities) {
        this.userId = userId;
        this.username = username;
        this.password = password;
        this.authorities = authorities;
    }

    /** 从实体构建（登录校验用） */
    public static ForumUserDetails from(User user) {
        List<SimpleGrantedAuthority> authorities = user.getRoles().stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r.getName()))
                .toList();
        return new ForumUserDetails(user.getId(), user.getUsername(), user.getPassword(), authorities);
    }

    /** 从 JWT Claims 构建（过滤器恢复认证态用，密码置空） */
    public static ForumUserDetails fromClaims(Long userId, String username, List<String> roles) {
        List<SimpleGrantedAuthority> authorities = roles.stream()
                .map(r -> new SimpleGrantedAuthority(r.startsWith("ROLE_") ? r : "ROLE_" + r))
                .toList();
        return new ForumUserDetails(userId, username, "", authorities);
    }

    public Long getUserId() {
        return userId;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
