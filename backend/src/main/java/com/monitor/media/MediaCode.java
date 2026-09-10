package com.monitor.media;

/**
 * 影像对外编码（《B侧接口契约_M0》§7：{@code mediaId} 形如 {@code "M001"}）。
 *
 * <p>编码由主键<b>派生</b>，不单独存列：影像没有人工录入的业务码
 * （不像 {@code P-HK01}/{@code radar-001} 那样有业务含义），
 * 派生可保证「一个 id 恒对应一个编码」，不存在两列写不一致或需要回填的情形。
 * 格式 {@code M + 3 位左补零}，超过 999 位自然加宽（{@code M1000}），不截断。</p>
 *
 * <p>读取接口同时容错裸数字 id（{@code /media/1/content} 与 {@code /media/M001/content} 等价），
 * 便于排障时直接拿库里的 id 试。</p>
 */
public final class MediaCode {

    private static final String PREFIX = "M";

    private MediaCode() {
    }

    /** 主键 -> 对外编码：{@code 1 -> "M001"}。 */
    public static String of(Long id) {
        return id == null ? null : PREFIX + String.format("%03d", id);
    }

    /** 对外编码 -> 主键；接受 {@code "M001"} 与裸数字 {@code "1"}，无法解析返回 {@code null}。 */
    public static Long parse(String code) {
        if (code == null) {
            return null;
        }
        String s = code.trim();
        if (!s.isEmpty() && (s.charAt(0) == 'M' || s.charAt(0) == 'm')) {
            s = s.substring(1);
        }
        if (s.isEmpty() || !s.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
