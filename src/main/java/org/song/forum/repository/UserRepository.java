package org.song.forum.repository;

import org.song.forum.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    /** 是否仍有其他用户引用同一个头像地址（用于避免删除共享文件）。 */
    boolean existsByAvatarUrlAndIdNot(String avatarUrl, Long id);
}
