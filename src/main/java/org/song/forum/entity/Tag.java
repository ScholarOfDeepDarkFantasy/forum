package org.song.forum.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * 标签
 */
@Entity
@Table(name = "tags")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Tag extends BaseEntity {

    @Column(nullable = false, length = 30, unique = true)
    private String name;
}
