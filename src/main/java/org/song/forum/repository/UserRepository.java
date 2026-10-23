package org.song.forum.repository;

import org.song.forum.entity.User;
import org.song.forum.entity.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    /** 是否仍有其他用户引用同一个头像地址（用于避免删除共享文件）。 */
    boolean existsByAvatarUrlAndIdNot(String avatarUrl, Long id);

    /**
     * 过滤器每请求只需三列：不加载 roles 集合，避免一次鉴权触发 N+1。
     * <p>投影必须写别名（as id / as status / as tokenVersion），否则 JPA 无法把元组映射到接口。</p>
     */
    @Query("select u.id as id, u.status as status, u.tokenVersion as tokenVersion "
            + "from User u where u.id = :id")
    Optional<UserTokenState> findTokenStateById(@Param("id") Long id);

    /** 只读投影：认证过滤器判定「这次请求还能不能建立认证态」。 */
    interface UserTokenState {

        Long getId();

        UserStatus getStatus();

        long getTokenVersion();
    }
}
