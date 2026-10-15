package org.song.forum.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.song.forum.entity.User;
import org.song.forum.repository.UserRepository;

/**
 * UserDetailsService：按用户名加载用户（登录认证入口）
 */
@Service
@RequiredArgsConstructor
public class ForumUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在: " + username));
        return ForumUserDetails.from(user);
    }
}
