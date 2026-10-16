package org.song.forum.dto.post;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 帖子详情响应：独立完整结构（不用继承，避免 Lombok @Builder 继承陷阱）
 */
@Data
@Builder
public class PostDetailResponse {

    private Long id;
    private Long boardId;
    private String boardName;
    private Long authorId;
    private String authorUsername;
    private String authorNickname;
    private String title;
    private String content;
    private Long viewCount;
    private Integer likeCount;
    private Integer favoriteCount;
    private Integer commentCount;
    private Boolean isPinned;
    private Boolean isExquisite;
    private List<String> tags;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 当前登录用户是否已点赞；游客恒为 false */
    @Builder.Default
    private Boolean liked = false;

    /** 当前登录用户是否已收藏；游客恒为 false */
    @Builder.Default
    private Boolean favorited = false;

    /** 从列表项 + 正文组装详情（继承列表项已按当前用户算好的互动状态） */
    public static PostDetailResponse of(PostSummaryResponse s, String content) {
        return PostDetailResponse.builder()
                .id(s.getId())
                .boardId(s.getBoardId())
                .boardName(s.getBoardName())
                .authorId(s.getAuthorId())
                .authorUsername(s.getAuthorUsername())
                .authorNickname(s.getAuthorNickname())
                .title(s.getTitle())
                .likeCount(s.getLikeCount())
                .commentCount(s.getCommentCount())
                .viewCount(s.getViewCount())
                .isPinned(s.getIsPinned())
                .isExquisite(s.getIsExquisite())
                .tags(s.getTags())
                .createTime(s.getCreateTime())
                .content(content)
                .liked(s.getLiked())
                .favorited(s.getFavorited())
                .build();
    }
}
