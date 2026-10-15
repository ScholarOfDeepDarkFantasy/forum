package org.song.forum.dto.board;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 板块创建/更新请求（管理员用）
 */
@Data
public class BoardRequest {

    @NotBlank(message = "板块名不能为空")
    @Size(max = 50, message = "板块名最长 50 字符")
    private String name;

    @Size(max = 255, message = "描述最长 255 字符")
    private String description;

    /** 排序权重，越小越靠前 */
    private Integer sortOrder;
}
