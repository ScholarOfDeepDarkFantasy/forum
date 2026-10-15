package org.song.forum.dto.user;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 管理员用户状态操作入参：禁言（MUTED + muteMinutes）或封禁（BANNED）
 */
@Data
public class UserStatusRequest {

    /** 目标状态：ACTIVE（解封）/ MUTED（禁言）/ BANNED（封禁） */
    @NotNull(message = "状态不能为空")
    private String status;

    /** 禁言时长（分钟），status=MUTED 时必填 */
    @Min(value = 1, message = "禁言时长至少 1 分钟")
    private Integer muteMinutes;
}
