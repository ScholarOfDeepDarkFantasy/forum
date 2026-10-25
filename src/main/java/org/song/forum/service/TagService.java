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
        // 关键词进 LIKE 前必须转义（T17）：'%'、'_' 是通配符，'!' 是 JPQL 里 ESCAPE 指定的转义符
        List<TagResponse> all = tagRepository.findWithPostCounts(escapeLike(kw)).stream()
                .map(row -> new TagResponse(
                        ((Number) row[0]).longValue(),
                        (String) row[1],
                        ((Number) row[2]).longValue()))
                .sorted(Comparator.comparingLong(TagResponse::postCount).reversed()
                        .thenComparing(TagResponse::name))
                .toList();

        // 分页用 long 运算再收窄：page=Integer.MAX_VALUE&size=100 时 int 乘法会溢出成负数，
        // subList(from, to) 随即抛 IndexOutOfBoundsException（500）。size 在此处再做一次兜底，
        // 保证 from/to 恒落在 [0, all.size()] 且 to >= from。
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(size, 1);
        long fromL = (long) safePage * safeSize;
        int from = (int) Math.min(fromL, all.size());
        int to = (int) Math.min(fromL + safeSize, all.size());
        if (to < from) {
            to = from;
        }
        // totalPages 同样用 long 计算，避免 (all.size()+size-1) 在 int 下溢出
        int totalPages = (int) ((all.size() + (long) safeSize - 1) / safeSize);
        return new PageResponse<>(all.subList(from, to), safePage, safeSize, all.size(), totalPages,
                to >= all.size());
    }

    /**
     * 转义 LIKE 通配符：'!' 是 JPQL 里 ESCAPE 指定的转义符，所以它自己也要先转义。
     * 与 PostService.escapeLike 同款（那边搜索/列表都靠它）；标签名允许 '%'、'_'
     * （PostService.resolveTags 只做 trim + 小写），所以标签搜索侧也必须转义（T17）。
     * 抽公共工具类不划算：只有这两个 Service 用，且两边注释各自解释了「为什么」。
     */
    private static String escapeLike(String keyword) {
        StringBuilder sb = new StringBuilder(keyword.length() + 8);
        for (int i = 0; i < keyword.length(); i++) {
            char c = keyword.charAt(i);
            if (c == '!' || c == '%' || c == '_') {
                sb.append('!');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
