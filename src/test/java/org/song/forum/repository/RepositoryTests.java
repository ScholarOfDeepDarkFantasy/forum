package org.song.forum.repository;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.song.forum.entity.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
        // 角色（import.sql 已预置 USER 角色，直接查用，避免与种子数据主键冲突）
        Role role = roleRepository.findByName("USER")
                .orElseThrow(() -> new AssertionError("import.sql 未预置 USER 角色"));
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
        // 列表查询走两步：先分页取骨架，再批量抓关联（search 的集合 EntityGraph 版已删除）
        Page<Post> skeletons = postRepository.searchIds(null, "forum", PageRequest.of(0, 10));
        assertThat(skeletons.getTotalElements()).isEqualTo(1);
        assertThat(postRepository.findWithAssociations(
                skeletons.getContent().stream().map(Post::getId).toList()))
                .singleElement()
                .satisfies(p -> assertThat(p.getTags()).extracting(Tag::getName).containsExactly("spring-boot"));
        assertThat(notificationRepository.countByRecipientIdAndIsReadFalse(author.getId())).isEqualTo(1);
    }

    /**
     * 全表加载回归守卫：列表查询路径不得触发 HHH90003004。
     * <p>该警告意味着 Hibernate 因"集合抓取 + 分页"把全部匹配行读进内存再截取，
     * 数据量增长后会变成性能事故。用 logback 捕获这段日志来断言。</p>
     */
    @Test
    void pagedListQueriesDoNotTriggerInMemoryPaging() {
        User author = user("warncheck");
        Board board = boardRepository.save(Board.builder().name("告警板块").description("验证无警告").build());
        Tag tag = tagRepository.save(Tag.builder().name("warn-tag").build());
        for (int i = 0; i < 12; i++) {
            Post p = postRepository.save(Post.builder()
                    .board(board).author(author).title("告警帖 " + i).content("内容 " + i).build());
            p.getTags().add(tag);
            postRepository.save(p);
        }
        em.flush();
        em.clear();

        Logger hibernateLogger = (Logger) LoggerFactory.getLogger("org.hibernate.orm.query");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        hibernateLogger.addAppender(appender);
        Page<Post> page;
        try {
            page = postRepository.searchIds(null, "", PageRequest.of(0, 5));
            postRepository.findWithAssociations(page.getContent().stream().map(Post::getId).toList());
        } finally {
            hibernateLogger.detachAppender(appender);
        }

        assertThat(page.getContent()).hasSize(5);
        assertThat(appender.list)
                .as("列表查询路径不应出现集合抓取分页警告")
                .noneMatch(e -> e.getFormattedMessage().contains("HHH90003004"));
    }

    @Test
    void uniqueConstraintsWork() {
        user("bob");
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> user("bob"));
    }

    /**
     * 分页 + 集合关联（tags）必须走两步查询：先分页取骨架，再按 id 批量抓 tags。
     * Hibernate 对"集合抓取 + 分页"会打印 HHH90003004 并把全部匹配行读进内存，
     * 这里用"第二页也能拿到正确关联"钉住该行为——全表加载实现下第二页的关联会缺失。
     */
    @Test
    void pagedSearchHydratesTagsViaTwoStep() {
        User author = user("pager");
        Board board = boardRepository.save(Board.builder().name("分页板块").description("验证分页").build());
        Tag t1 = tagRepository.save(Tag.builder().name("page-tag-a").build());
        Tag t2 = tagRepository.save(Tag.builder().name("page-tag-b").build());

        for (int i = 0; i < 15; i++) {
            Post p = postRepository.save(Post.builder()
                    .board(board).author(author)
                    .title("分页帖 " + i).content("内容 " + i)
                    .build());
            p.getTags().add(i % 2 == 0 ? t1 : t2);
            postRepository.save(p);
        }
        em.flush();
        em.clear();

        Page<Post> firstPage = postRepository.searchIds(null, "", PageRequest.of(0, 10));
        Page<Post> secondPage = postRepository.searchIds(null, "", PageRequest.of(1, 10));

        assertThat(firstPage.getTotalElements()).isEqualTo(15);
        assertThat(firstPage.getContent()).hasSize(10);
        assertThat(secondPage.getContent()).hasSize(5);

        // 第二步：按 id 批量抓详情，tags 必须已初始化
        List<Long> secondPageIds = secondPage.getContent().stream().map(Post::getId).toList();
        List<Post> hydrated = postRepository.findWithAssociations(new HashSet<>(secondPageIds));
        assertThat(hydrated).hasSize(5);
        assertThat(hydrated).allSatisfy(p -> {
            assertThat(p.getTags()).isNotEmpty();
            assertThat(p.getBoard().getName()).isEqualTo("分页板块");
            assertThat(p.getAuthor().getUsername()).isEqualTo("pager");
        });
    }

    /**
     * "我的帖子"与搜索是同一种查询形态，同样不得在集合抓取上分页。
     */
    @Test
    void pagedMyPostsHydratesTagsViaTwoStep() {
        User author = user("minepager");
        Board board = boardRepository.save(Board.builder().name("我的板块").description("验证我的帖子分页").build());
        Tag tag = tagRepository.save(Tag.builder().name("mine-tag").build());

        for (int i = 0; i < 3; i++) {
            Post p = postRepository.save(Post.builder()
                    .board(board).author(author)
                    .title("我的帖 " + i).content("内容 " + i)
                    .build());
            p.getTags().add(tag);
            postRepository.save(p);
        }
        em.flush();
        em.clear();

        Page<Post> page = postRepository.findIdsByAuthorIdAndDeletedFalse(author.getId(), PageRequest.of(0, 10));
        assertThat(page.getContent()).hasSize(3);

        List<Post> hydrated = postRepository.findWithAssociations(
                page.getContent().stream().map(Post::getId).toList());
        assertThat(hydrated).hasSize(3);
        assertThat(hydrated).allSatisfy(p -> assertThat(p.getTags()).extracting(Tag::getName).containsExactly("mine-tag"));
    }

    /**
     * 收藏列表：FavoriteRepository 的 @EntityGraph 未覆盖 post.tags，
     * 第二步按 id 批量补齐后才能拿到标签（否则每行触发一次 post_tags 查询）。
     */
    @Test
    void pagedFavoritesHydrateTags() {
        User fan = user("fan");
        Board board = boardRepository.save(Board.builder().name("收藏板块").description("验证收藏分页").build());
        Tag tag = tagRepository.save(Tag.builder().name("fav-tag").build());
        Post post = postRepository.save(Post.builder()
                .board(board).author(fan).title("被收藏").content("内容").build());
        post.getTags().add(tag);
        postRepository.save(post);
        favoriteRepository.save(Favorite.builder().user(fan).post(post).build());
        em.flush();
        em.clear();

        Page<Favorite> page = favoriteRepository
                .findByUserIdOrderByCreateTimeDesc(fan.getId(), PageRequest.of(0, 10));
        assertThat(page.getContent()).hasSize(1);

        Set<Long> postIds = page.getContent().stream()
                .map(f -> f.getPost().getId()).collect(Collectors.toSet());
        List<Post> hydrated = postRepository.findWithAssociations(postIds);
        assertThat(hydrated).hasSize(1);
        assertThat(hydrated.get(0).getTags()).extracting(Tag::getName).containsExactly("fav-tag");
    }
}
