package com.monitor.common.util;

import com.monitor.common.exception.BizException;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 时间口径统一：对外一律 ISO8601 带时区（契约要求），库内为 {@link LocalDateTime}。
 */
public final class Times {

    /** 与 application.yml 的 jackson time-zone 一致。 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Times() {
    }

    /** {@code LocalDateTime} → ISO8601 带时区，如 {@code 2026-08-27T10:10:00+08:00}。 */
    public static String iso(LocalDateTime t) {
        return t == null ? null
                : t.atZone(ZONE).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /**
     * 解析入参时间，容忍三种写法：ISO8601 带时区、ISO8601 本地、{@code yyyy-MM-dd HH:mm:ss}。
     *
     * @param field 出错时用于提示的字段名
     */
    public static LocalDateTime parse(String s, String field) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(s).atZoneSameInstant(ZONE).toLocalDateTime();
        } catch (Exception ignored) {
            // 继续尝试无时区写法
        }
        try {
            return LocalDateTime.parse(s);
        } catch (Exception ignored) {
            // 继续尝试空格分隔写法
        }
        try {
            return LocalDateTime.parse(s, LOCAL);
        } catch (Exception e) {
            throw new BizException(field + " 时间格式非法（应为 ISO8601）: " + s);
        }
    }
}
