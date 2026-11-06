package org.song.forum.exception;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 错误文案解析：全站唯一入口，负责把请求 locale 归一成「中文（默认）」或「英文」。
 *
 * <p>为什么不在 MessageSource 层配 supportedLocales：Security 过滤器在 MVC 之外，
 * 拿不到 LocaleContext，只能 {@code request.getLocale()}；而容器 JVM 默认 locale 常是 en，
 * 不带 Accept-Language 的 curl 会拿到英文。MVC 侧由 {@code spring.web.locale=zh_CN} 固定
 * 默认值，Security 侧由调用方在「没有 Accept-Language 头」时传 null；这里只认显式 en，
 * 其余（zh、fr、null、系统默认）一律回退中文。</p>
 */
@Component
@RequiredArgsConstructor
public class ErrorMessages {

    private final MessageSource messageSource;

    /** MVC 内使用：locale 来自 LocaleContextHolder（Accept-Language 解析结果）。 */
    public String get(ErrorCode code, Object... args) {
        return getForLocale(code, LocaleContextHolder.getLocale(), args);
    }

    /** Security 过滤器内使用：locale 由调用方显式传入（无 Accept-Language 时应传 null）。 */
    public String getForLocale(ErrorCode code, Locale locale, Object... args) {
        Object[] effective = (args == null || args.length == 0) ? null : args;
        // 第三个参数是缺键兜底：资源缺失/未打包时至少返回错误码本身，而不是抛异常
        return messageSource.getMessage(code.getMessageKey(), effective, code.name(), normalize(locale));
    }

    /** 只有显式英文才走英文资源；其他一律用默认（中文）资源。 */
    static Locale normalize(Locale locale) {
        return locale != null && "en".equalsIgnoreCase(locale.getLanguage())
                ? Locale.ENGLISH
                : Locale.SIMPLIFIED_CHINESE;
    }
}
