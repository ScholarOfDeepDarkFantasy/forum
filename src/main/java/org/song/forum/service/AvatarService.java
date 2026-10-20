package org.song.forum.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.song.forum.dto.user.UserResponse;
import org.song.forum.entity.User;
import org.song.forum.exception.BusinessException;
import org.song.forum.repository.UserRepository;
import org.song.forum.util.AvatarImageProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 头像编排：校验 → 转码 → 临时文件 → 原子改名 → 更新 users.avatar_url → 提交后清旧文件。
 * <p>文件名是服务端生成的 UUID，客户端文件名不参与路径拼接；提交前不删旧文件，
 * 数据库回滚时旧头像仍可用。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AvatarService {

    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final String AVATAR_URL_PREFIX = "/uploads/avatars/";
    /** 只有服务端生成的 UUID 文件名才允许删除，历史用户自填 URL 一律不动 */
    private static final Pattern SERVER_FILE_NAME = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg$");

    private final UserRepository userRepository;

    @Value("${app.upload-dir}")
    private String uploadDir;

    @Transactional
    public UserResponse upload(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择图片文件");
        }
        // 双保险之一：multipart 限制之外再挡一次，避免配置被改小后失效
        if (file.getSize() > MAX_BYTES) {
            throw new BusinessException("图片不能超过 2MB");
        }
        byte[] raw;
        try {
            raw = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("读取图片失败");
        }
        byte[] jpeg = AvatarImageProcessor.process(raw);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("用户不存在"));
        String oldUrl = user.getAvatarUrl();
        // 旧文件是否仍被其他用户引用必须在本事务内查完；提交回调里不再访问数据库
        boolean oldFileDeletable = isServerGenerated(oldUrl)
                && !userRepository.existsByAvatarUrlAndIdNot(oldUrl, userId);

        Path avatarDir = avatarDir();
        String filename = UUID.randomUUID() + ".jpg";
        Path target = avatarDir.resolve(filename);
        try {
            Files.createDirectories(avatarDir);
            writeAtomically(avatarDir, target, jpeg);
        } catch (IOException e) {
            log.error("保存头像失败: dir={}", avatarDir, e);
            deleteQuietly(target);
            throw new BusinessException("保存头像失败");
        }

        user.setAvatarUrl(AVATAR_URL_PREFIX + filename);
        try {
            user = userRepository.save(user);
        } catch (RuntimeException e) {
            // 数据库没提交成功，刚写入的新文件不能留下
            deleteQuietly(target);
            throw e;
        }
        if (oldFileDeletable) {
            deleteAfterCommit(oldUrl);
        }
        log.info("用户 {} 更新头像: {}", user.getUsername(), user.getAvatarUrl());
        return UserResponse.from(user);
    }

    /** 幂等：没有头像时直接返回当前用户 */
    @Transactional
    public UserResponse remove(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("用户不存在"));
        String oldUrl = user.getAvatarUrl();
        if (oldUrl == null) {
            return UserResponse.from(user);
        }
        boolean oldFileDeletable = isServerGenerated(oldUrl)
                && !userRepository.existsByAvatarUrlAndIdNot(oldUrl, userId);
        user.setAvatarUrl(null);
        user = userRepository.save(user);
        if (oldFileDeletable) {
            deleteAfterCommit(oldUrl);
        }
        return UserResponse.from(user);
    }

    private Path avatarDir() {
        return Paths.get(uploadDir, "avatars");
    }

    /** 同目录内临时文件写完再原子改名，静态资源不会读到写了一半的文件 */
    private void writeAtomically(Path dir, Path target, byte[] jpeg) throws IOException {
        Path tmp = Files.createTempFile(dir, "avatar-", ".tmp");
        try {
            Files.write(tmp, jpeg);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** 事务提交后再删旧文件；没有事务（如直接调用）时立即删 */
    private void deleteAfterCommit(String avatarUrl) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteAvatarFile(avatarUrl);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteAvatarFile(avatarUrl);
            }
        });
    }

    /** 只删除服务端命名的文件，且调用方已确认没有其他用户引用它 */
    private void deleteAvatarFile(String avatarUrl) {
        if (!isServerGenerated(avatarUrl)) {
            return;
        }
        String filename = avatarUrl.substring(AVATAR_URL_PREFIX.length());
        Path dir = avatarDir();
        try {
            Path target = dir.resolve(filename).toRealPath();
            if (!target.startsWith(dir.toRealPath())) {
                return;
            }
            Files.deleteIfExists(target);
        } catch (NoSuchFileException e) {
            log.debug("旧头像文件已不存在: {}", avatarUrl);
        } catch (IOException e) {
            log.warn("删除旧头像失败: {}", avatarUrl, e);
        }
    }

    private static boolean isServerGenerated(String avatarUrl) {
        if (avatarUrl == null || !avatarUrl.startsWith(AVATAR_URL_PREFIX)) {
            return false;
        }
        String filename = avatarUrl.substring(AVATAR_URL_PREFIX.length());
        return !filename.contains("/") && SERVER_FILE_NAME.matcher(filename).matches();
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("清理临时头像文件失败: {}", path, e);
        }
    }
}
