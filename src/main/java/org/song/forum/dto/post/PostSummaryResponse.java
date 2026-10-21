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

    /** 当前登录用户是否已点赞；游客恒为 false */
    @Builder.Default
    private Boolean liked = false;

    /** 当前登录用户是否已收藏；游客恒为 false */
    @Builder.Default
    private Boolean favorited = false;

    /**
     * 正文命中处的纯文本窗口（左右各 80 字符，未清洗 Markdown、不含省略号）。
     * <p>只有全文搜索接口会填：搜索页需要在标题下方展示「正文命中上下文」，
     * 而列表 DTO 刻意不带完整正文（避免搜索页响应膨胀）。标题命中且正文不含
     * 关键词时为 {@code ""}（前端据此不渲染片段行），其它列表接口保持 {@code null}。</p>
     */
    private String snippet;
}
