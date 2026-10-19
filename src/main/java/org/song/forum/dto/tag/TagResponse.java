package org.song.forum.dto.tag;

/** 标签总览项：名称 + 未删除帖子数 */
public record TagResponse(Long id, String name, long postCount) {
}
