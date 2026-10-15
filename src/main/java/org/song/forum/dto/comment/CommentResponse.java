package org.song.forum.dto.comment;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 评论响应：树形结构（replies 内嵌子回复，均为平铺一层楼中楼）。
 * 软删除的评论返回占位（content="该评论已删除"，author 字段置空，deleted=true）。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CommentResponse {

    private Long id;

    private Long postId;

    private Long parentId;

    private Long authorId;
    private String authorUsername;
    private String authorNickname;
    private String authorAvatar;

    private String content;

    private Boolean deleted;

    private LocalDateTime createTime;

    @Builder.Default
    private List<CommentResponse> replies = new ArrayList<>();
}
