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
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * 头像编排：校验 → 转码 → 落盘 → 更新 users.avatar_url → 清旧文件。
 * <p>文件一定被重编码为 JPEG，文件名是服务端生成的 UUID，
 * 客户端文件名不参与路径拼接。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AvatarService {

    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final String AVATAR_URL_PREFIX = "/uploads/avatars/";

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
        Path avatarDir = avatarDir();
        String filename = UUID.randomUUID() + ".jpg";
        try {
            Files.createDirectories(avatarDir);
            Files.write(avatarDir.resolve(filename), jpeg);
        } catch (IOException e) {
            log.error("保存头像失败: dir={}", avatarDir, e);
            throw new BusinessException("保存头像失败");
        }

        String oldUrl = user.getAvatarUrl();
        user.setAvatarUrl(AVATAR_URL_PREFIX + filename);
        user = userRepository.save(user);
        deleteAvatarFile(oldUrl);
        log.info("用户 {} 更新头像: {}", user.getUsername(), user.getAvatarUrl());
        return toUserResponse(user);
    }

    /** 幂等：没有头像时直接返回当前用户 */
    @Transactional
    public UserResponse remove(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("用户不存在"));
        String oldUrl = user.getAvatarUrl();
        if (oldUrl == null) {
            return toUserResponse(user);
        }
        user.setAvatarUrl(null);
        user = userRepository.save(user);
        deleteAvatarFile(oldUrl);
        return toUserResponse(user);
    }

    private Path avatarDir() {
        return Paths.get(uploadDir, "avatars");
    }

    /** 只删除自己目录里、由本服务命名的文件；路径穿越/外链一律跳过 */
    private void deleteAvatarFile(String avatarUrl) {
        if (avatarUrl == null || !avatarUrl.startsWith(AVATAR_URL_PREFIX)) {
            return;
        }
        String filename = avatarUrl.substring(AVATAR_URL_PREFIX.length());
        if (filename.isBlank() || filename.contains("/") || filename.contains("..")) {
            return;
        }
        Path dir = avatarDir();
        try {
            Path target = dir.resolve(filename).toRealPath();
            if (!target.startsWith(dir.toRealPath())) {
                return;
            }
            Files.deleteIfExists(target);
        } catch (IOException e) {
            log.warn("删除旧头像失败: {}", avatarUrl, e);
        }
    }

    private UserResponse toUserResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatarUrl(user.getAvatarUrl())
                .bio(user.getBio())
                .createTime(user.getCreateTime())
                .build();
    }
}
