package org.song.forum.security;

/**
 * 一条清洗后的 CSP 违规记录。
 *
 * <p>只保留白名单字段，生命周期仅限一次日志输出——不落库、不回显，避免把页面 URL
 * 的查询串或用户内容持久化。</p>
 *
 * @param documentUri 触发违规的页面地址（已去掉 query/fragment，最多 300 字符）
 * @param directive   生效指令（effective-directive，缺省 violated-directive，再缺省 unknown）
 * @param blockedUri  被拦截的资源（已去掉 query/fragment，最多 300 字符）
 * @param sourceFile  来源脚本文件（已去掉 query/fragment，最多 300 字符）
 * @param sample      触发样本（已去控制字符，最多 120 字符）
 * @param disposition 处置方式（enforce / report）
 */
public record CspViolation(String documentUri, String directive, String blockedUri,
                           String sourceFile, String sample, String disposition) {
}
