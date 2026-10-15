package org.song.forum.dto.comment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发表评论/回复入参
 */
@Data
public class CommentRequest {

    @NotBlank(message = "评论内容不能为空")
    @Size(max = 1000, message = "评论内容不能超过 1000 字")
    private String content;

    /** 父评论 id，为空表示顶级评论 */
    private Long parentId;
}
