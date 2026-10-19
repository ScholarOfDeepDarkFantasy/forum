package org.song.forum.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.song.forum.dto.tag.TagResponse;
import org.song.forum.repository.TagRepository;
import org.song.forum.util.PageResponse;

import java.util.Comparator;
import java.util.List;

/** 标签总览：聚合查询 + 排序 + 服务层分页 */
@Service
@RequiredArgsConstructor
public class TagService {

    private final TagRepository tagRepository;

    @Transactional(readOnly = true)
    public PageResponse<TagResponse> list(String keyword, int page, int size) {
        String kw = (keyword == null || keyword.isBlank()) ? "" : keyword.trim();
        List<TagResponse> all = tagRepository.findWithPostCounts(kw).stream()
                .map(row -> new TagResponse(
                        ((Number) row[0]).longValue(),
                        (String) row[1],
                        ((Number) row[2]).longValue()))
                .sorted(Comparator.comparingLong(TagResponse::postCount).reversed()
                        .thenComparing(TagResponse::name))
                .toList();

        int from = Math.min(page * size, all.size());
        int to = Math.min(from + size, all.size());
        int totalPages = size > 0 ? (int) Math.ceil((double) all.size() / size) : 0;
        return new PageResponse<>(all.subList(from, to), page, size, all.size(), totalPages, to >= all.size());
    }
}
