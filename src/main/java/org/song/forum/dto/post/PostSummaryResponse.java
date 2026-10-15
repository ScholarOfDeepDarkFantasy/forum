package org.song.forum.dto.post;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 帖子列表项（轻量，无 content 正文）
 */
@Data
@Builder
public class PostSummaryResponse {

    private Long id;
    private Long boardId;
    private String boardName;
    private Long authorId;
    private String authorUsername;
    private String authorNickname;
    private String title;
    private Integer likeCount;
    private Integer commentCount;
    private Long viewCount;
    private Boolean isPinned;
    private Boolean isExquisite;
    private List<String> tags;
    private LocalDateTime createTime;
}
