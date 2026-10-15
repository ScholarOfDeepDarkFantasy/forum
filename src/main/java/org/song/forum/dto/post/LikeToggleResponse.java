package org.song.forum.dto.post;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 点赞/收藏切换结果：操作后的状态与最新计数
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LikeToggleResponse {

    /** 操作后是否处于点赞/收藏状态 */
    private boolean active;

    /** 帖子最新点赞数/收藏数 */
    private long count;
}
