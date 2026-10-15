package org.song.forum.dto.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新个人资料请求（用户名与邮箱不可改，MVP 简化）
 */
@Data
public class UpdateProfileRequest {

    @Size(max = 50, message = "昵称过长")
    private String nickname;

    @Size(max = 255, message = "头像地址过长")
    private String avatarUrl;

    @Size(max = 500, message = "个人简介过长")
    private String bio;

    @Email(message = "邮箱格式不正确")
    private String emailPlaceholder; // 预留，暂不启用
}
