package org.song.forum.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.song.forum.entity.User;
import org.song.forum.entity.UserStatus;
import org.song.forum.repository.UserRepository;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 落盘/数据库事务顺序的单元测试：用 mock 仓库模拟保存失败，验证旧文件不会被误删、新文件不会残留。
 */
@ExtendWith(MockitoExtension.class)
class AvatarServiceTest {

    @Mock
    private UserRepository userRepository;

    @TempDir
    Path uploadDir;

    private AvatarService avatarService;
    private Path avatarDir;

    @BeforeEach
    void setUp() throws IOException {
        avatarService = new AvatarService(userRepository);
        ReflectionTestUtils.setField(avatarService, "uploadDir", uploadDir.toString());
        avatarDir = Files.createDirectories(uploadDir.resolve("avatars"));
    }

    private static MockMultipartFile jpegUpload() throws IOException {
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.ORANGE);
        graphics.fillRect(0, 0, 20, 20);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return new MockMultipartFile("file", "photo.jpg", "image/jpeg", out.toByteArray());
    }

    private static User userWithAvatar(Long id, String avatarUrl) {
        User user = User.builder()
                .username("avatar-user")
                .password("x")
                .email("avatar-user@test.com")
                .nickname("头像用户")
                .status(UserStatus.ACTIVE)
                .avatarUrl(avatarUrl)
                .build();
        user.setId(id);
        return user;
    }

    private static String randomFileName() {
        return UUID.randomUUID() + ".jpg";
    }

    private List<String> storedFiles() throws IOException {
        try (Stream<Path> files = Files.list(avatarDir)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void saveFailureKeepsOldFileAndRemovesNewFile() throws IOException {
        String oldName = randomFileName();
        Path oldFile = Files.write(avatarDir.resolve(oldName), new byte[]{1, 2, 3});
        User user = userWithAvatar(1L, "/uploads/avatars/" + oldName);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenThrow(new IllegalStateException("数据库不可用"));

        assertThatThrownBy(() -> avatarService.upload(1L, jpegUpload()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("数据库不可用");

        assertThat(oldFile).exists();
        assertThat(storedFiles()).containsExactly(oldName);
    }

    @Test
    void saveFailureDuringRemoveKeepsAvatarFile() throws IOException {
        String oldName = randomFileName();
        Path oldFile = Files.write(avatarDir.resolve(oldName), new byte[]{1, 2, 3});
        User user = userWithAvatar(2L, "/uploads/avatars/" + oldName);
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenThrow(new IllegalStateException("数据库不可用"));

        assertThatThrownBy(() -> avatarService.remove(2L))
                .isInstanceOf(IllegalStateException.class);

        assertThat(oldFile).exists();
    }

    @Test
    void legacyNonServerFileNameIsNeverDeleted() throws IOException {
        Path legacyFile = Files.write(avatarDir.resolve("legacy.jpg"), new byte[]{1, 2, 3});
        User user = userWithAvatar(3L, "/uploads/avatars/legacy.jpg");
        when(userRepository.findById(3L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        avatarService.upload(3L, jpegUpload());

        assertThat(legacyFile).exists();
    }

    @Test
    void avatarStillReferencedByAnotherUserIsKept() throws IOException {
        String sharedName = randomFileName();
        Path sharedFile = Files.write(avatarDir.resolve(sharedName), new byte[]{1, 2, 3});
        String sharedUrl = "/uploads/avatars/" + sharedName;
        User user = userWithAvatar(4L, sharedUrl);
        when(userRepository.findById(4L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.existsByAvatarUrlAndIdNot(sharedUrl, 4L)).thenReturn(true);

        avatarService.remove(4L);

        assertThat(sharedFile).exists();
    }
}
