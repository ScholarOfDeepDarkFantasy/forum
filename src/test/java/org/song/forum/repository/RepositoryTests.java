package org.song.forum.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.song.forum.entity.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository 集成测试：H2（PostgreSQL 兼容模式）跑真实 SQL，验证实体映射与 Schema 一致
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:repo-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class RepositoryTests {

    @Autowired
    TestEntityManager em;

    @Autowired
    UserRepository userRepository;
    @Autowired
    RoleRepository roleRepository;
    @Autowired
    BoardRepository boardRepository;
    @Autowired
    PostRepository postRepository;
    @Autowired
    CommentRepository commentRepository;
    @Autowired
    TagRepository tagRepository;
    @Autowired
    LikeRepository likeRepository;
    @Autowired
    FavoriteRepository favoriteRepository;
    @Autowired
    NotificationRepository notificationRepository;

    private User user(String name) {
        return userRepository.save(User.builder()
                .username(name).password("bcrypt-hash").email(name + "@test.com")
                .status(UserStatus.ACTIVE).build());
    }

    @Test
    void saveAndQueryFullChain() {
        // 角色
        Role role = roleRepository.save(Role.builder().name("USER").build());
        assertThat(role.getId()).isNotNull();

        // 用户 + 板块 + 帖子 + 标签
        User author = user("alice");
        Board board = boardRepository.save(Board.builder().name("Java").description("Java 讨论").build());
        Tag tag = tagRepository.save(Tag.builder().name("spring-boot").build());

        Post post = postRepository.save(Post.builder()
                .board(board).author(author).title("Hello Forum").content("First post!")
                .isPinned(true).build());
        post.getTags().add(tag);
        postRepository.save(post);

        // 评论（含楼中楼）
        Comment top = commentRepository.save(Comment.builder()
                .post(post).author(author).content("top comment").build());
        commentRepository.save(Comment.builder()
                .post(post).author(author).parent(top).content("nested reply").build());

        // 点赞 / 收藏 / 通知
        likeRepository.save(Like.builder().user(author).post(post).build());
        favoriteRepository.save(Favorite.builder().user(author).post(post).build());
        notificationRepository.save(Notification.builder()
                .recipient(author).sender(author).post(post)
                .type(NotificationType.LIKE).content("有人赞了你的帖子").build());

        em.flush();
        em.clear();

        // ---- 验证 ----
        assertThat(postRepository.findWithDetailsById(post.getId()))
                .hasValueSatisfying(p -> {
                    assertThat(p.getBoard().getName()).isEqualTo("Java");
                    assertThat(p.getAuthor().getUsername()).isEqualTo("alice");
                    assertThat(p.getTags()).extracting(Tag::getName).containsExactly("spring-boot");
                    assertThat(p.getIsPinned()).isTrue();
                    assertThat(p.getCreateTime()).isNotNull();
                    assertThat(p.getUpdateTime()).isNotNull();
                });
        assertThat(commentRepository.countByPostIdAndDeletedFalse(post.getId())).isEqualTo(2);
        assertThat(commentRepository.findByParentIdAndDeletedFalseOrderByIdAsc(top.getId())).hasSize(1);
        assertThat(likeRepository.countByPostId(post.getId())).isEqualTo(1);
        assertThat(postRepository.search(null, "forum", org.springframework.data.domain.PageRequest.of(0, 10)))
                .totalElementsEqualTo(1);
        assertThat(notificationRepository.countByRecipientIdAndIsReadFalse(author.getId())).isEqualTo(1);
    }

    @Test
    void uniqueConstraintsWork() {
        user("bob");
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> user("bob"));
    }
}
