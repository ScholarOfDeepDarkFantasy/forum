package org.song.forum.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.HashSet;
import java.util.Set;

/**
 * 角色（种子数据：USER / MODERATOR / ADMIN，由 Flyway V1 预置）
 */
@Entity
@Table(name = "roles")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Role extends BaseEntity {

    @Column(nullable = false, length = 20, unique = true)
    private String name;
}
