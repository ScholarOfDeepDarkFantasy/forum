package org.song.forum.entity;

/**
 * 用户状态
 */
public enum UserStatus {
    /** 正常 */
    ACTIVE,
    /** 禁言（mutedUntil 到期自动恢复） */
    MUTED,
    /** 封禁 */
    BANNED
}
