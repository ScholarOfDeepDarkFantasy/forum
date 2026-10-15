package org.song.forum.dto.post;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 发帖/编辑帖子请求
 */
@Data
public class PostRequest {

    @NotNull(message = "板块不能为空")
    private Long boardId;

    @NotBlank(message = "标题不能为空")
    @Size(max = 200, message = "标题最长 200 字符")
    private String title;

    @NotBlank(message = "内容不能为空")
    private String content;

    /** 标签名列表，不存在的标签自动创建 */
    @Size(max = 5, message = "最多 5 个标签")
    private List<String> tags;
}
