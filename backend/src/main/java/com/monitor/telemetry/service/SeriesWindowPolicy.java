package com.monitor.telemetry.service;

import com.monitor.common.exception.BizException;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * {@code /points/{id}/series} 的时间窗与规模口径（复查清单 P0-3）。
 *
 * <p><b>为什么要有这个类</b>：改造前 {@code from}/{@code to} 都可省略，省略时条件**整条消失**，
 * 于是"不传时间窗"等于"把该测点全部历史拉进 JVM 再分桶"。演示库上那只是几十行，
 * 而本仓自己记录的生产基线是 1000 点 × 5 秒 ≈ 3456 万行/天——任何一个前端写错的调用
 * 都可以把 JVM 撑爆。默认窗口、最大跨度、原始点数上限三件事都只在这里定义一次，
 * 免得三处各写一个数、日后只改了其中一处。</p>
 *
 * <p>取值理由：</p>
 * <ul>
 *   <li><b>默认 24 小时</b>——与前端默认档位（测点页「近 24 小时」）同口径，
 *       也是"打开页面看趋势"最常用的窗口；</li>
 *   <li><b>最大跨度 31 天</b>——31 天覆盖"月报"这一档；再宽就不是趋势图，
 *       而是数据导出，应该走导出通道（当前没有，见清单 P1-1）；</li>
 *   <li><b>原始点数 5000</b>——raw 是"逐行返回"，5000 点约 0.5MB JSON，
 *       是浏览器与接口都能舒服处理的量级；超过它应当用 hour/day 分桶看趋势。</li>
 * </ul>
 *
 * <p>超限一律 **400 + 说明**，不静默截断：截断过的曲线看起来和真的一样，
 * 而"少了后半段"这件事在图上是看不出来的——那正是形变监测里最不能出的错。</p>
 */
public final class SeriesWindowPolicy {

    /** 未给窗口时的默认长度（前端"近 24 小时"档同值）。 */
    public static final Duration DEFAULT_WINDOW = Duration.ofHours(24);

    /** 单次查询允许的最大时间跨度。 */
    public static final Duration MAX_SPAN = Duration.ofDays(31);

    /** {@code granularity=raw} 时允许返回的最大点数（超过则 400，提示改用分桶）。 */
    public static final int MAX_RAW_POINTS = 5000;

    private SeriesWindowPolicy() {
    }

    /**
     * 解析后的窗口。
     *
     * @param from         窗口下界（含）
     * @param to           窗口上界（含）
     * @param fromDefaulted {@code from} 是否由默认窗口补出（响应里回显用，让"为什么只有这些点"可自证）
     * @param toDefaulted   {@code to} 是否由默认窗口补出
     */
    public record Window(LocalDateTime from, LocalDateTime to,
                         boolean fromDefaulted, boolean toDefaulted) {
    }

    /**
     * 把入参补成一段合法窗口。
     *
     * <p>补法（{@code now} 由调用方传入，便于单测与"同一请求内只取一次现在"）：</p>
     * <ul>
     *   <li>都给：原样使用；</li>
     *   <li>只给 {@code from}：上界取 {@code now}（不是无穷远）；</li>
     *   <li>只给 {@code to}：下界取 {@code to - 24h}（贴着用户关心的那一端，而不是从 1970 开始）；</li>
     *   <li>都不给：{@code [now-24h, now]}。</li>
     * </ul>
     */
    public static Window resolve(LocalDateTime from, LocalDateTime to, LocalDateTime now) {
        boolean fromDefaulted = from == null;
        boolean toDefaulted = to == null;
        LocalDateTime f = from;
        LocalDateTime t = to;
        if (f == null && t == null) {
            t = now;
            f = t.minus(DEFAULT_WINDOW);
        } else if (f == null) {
            f = t.minus(DEFAULT_WINDOW);
        } else if (t == null) {
            t = now;
        }
        if (f.isAfter(t)) {
            throw new BizException("from 不能晚于 to");
        }
        if (Duration.between(f, t).compareTo(MAX_SPAN) > 0) {
            throw new BizException("时间跨度不能超过 " + MAX_SPAN.toDays() + " 天：请缩小窗口，"
                    + "或改用 hour/day 分桶后按更短的窗口查询");
        }
        return new Window(f, t, fromDefaulted, toDefaulted);
    }
}
