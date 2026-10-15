package org.song.forum.dto.board;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 板块响应
 */
@Data
@Builder
public class BoardResponse {

    private Long id;
    private String name;
    private String description;
    private Integer sortOrder;
    private Long postCount;        // 该板块未删除帖子数
    private LocalDateTime createTime;
}
