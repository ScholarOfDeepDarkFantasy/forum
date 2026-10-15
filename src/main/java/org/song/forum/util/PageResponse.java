package org.song.forum.util;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 分页响应 DTO：用具体 POJO 替代 Spring Data 的 Page 接口序列化，
 * 避免 Jackson 无法实例化 Page 接口（测试反序列化 & 前端消费都更稳定）。
 * 字段名与 Page 序列化习惯一致（content/totalElements/...），前端迁移成本低。
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last
) {
    public static <T> PageResponse<T> of(Page<T> p) {
        return new PageResponse<>(
                p.getContent(),
                p.getNumber(),
                p.getSize(),
                p.getTotalElements(),
                p.getTotalPages(),
                p.isLast()
        );
    }
}
