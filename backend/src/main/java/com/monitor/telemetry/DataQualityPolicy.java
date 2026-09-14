package com.monitor.telemetry;

import com.monitor.telemetry.entity.Measurement;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据可信度判定口径（唯一实现），供 {@code DataQualityMonitor} 与告警快照共用。
 *
 * <p>与 {@code DeviceStatusPolicy} 同一条理由：判据集中一处，避免「监视器按一套阈值判定、
 * 展示按另一套解释」这种两套口径漂移。因此这里是**常量**而不是配置项——
 * 可配的只有扫描间隔（{@code monitor.data-quality.sweep-ms}），与设备离线扫描一致。</p>
 *
 * <h3>两个判据</h3>
 * <ul>
 *   <li><b>数据质量异常</b>：设备**在报数**（所以离线告警管不着它），但窗口内收到的测量值
 *       坏质量（{@code SUSPECT}/{@code FAULT}）占比过高 —— 值不可信。</li>
 *   <li><b>数据延迟上报</b>：设备采到了数据却迟迟不送，窗口内样本的
 *       {@code receiveTime - collectTime} 超阈值占比过高。</li>
 * </ul>
 *
 * <h3>为什么两个判据都要求「占比」而不看单条</h3>
 * 单条坏值/单次迟到是链路常态，据此报警会把告警栏刷成噪音，真正的险情反而被淹。
 * 故要求窗口内**样本数够**且**坏样本占比够**，两条都满足才算「持续」。
 *
 * <h3>延迟判据为什么还要卡 {@code collectTime} 也落在窗口内</h3>
 * 否则**回补历史数据会被判成延迟上报**：{@code radar_csv_replay} 回放的是真雷达的旧 CSV、
 * 03 套件为了画曲线的分桶也会写过去时刻的点，它们的 {@code receiveTime - collectTime}
 * 天然是几天甚至几十天——那是「补数据」，不是「设备现在送得慢」。
 * 卡上 collectTime ∈ 窗口之后，「延迟」才真的是延迟：**这条数据是刚采的，却过了很久才到**。
 * 顺带把可触发区间收成 {@code (delayMinutes, windowMinutes]} 这个窄带，测试数据偶发撞上
 * 的概率极低，不会污染其余套件的断言。
 */
public final class DataQualityPolicy {

    /** 判定窗口（分钟）：只看最近这么久内**收到**的数据。 */
    public static final int WINDOW_MINUTES = 15;

    /** 窗口内至少要有这么多条样本才判——只有一两条就说「全是坏值」是噪音。 */
    public static final int MIN_SAMPLES = 4;

    /** 坏样本占比达到该值即判定。0.6 = 六成及以上。 */
    public static final double BAD_RATIO = 0.6;

    /** 接收时间落后采集时间超过该分钟数，算这条数据「延迟上报」。 */
    public static final int DELAY_MINUTES = 10;

    private DataQualityPolicy() {
    }

    /** 判定窗口的起点（相对当前时刻）。 */
    public static LocalDateTime windowStart(LocalDateTime now) {
        return now.minusMinutes(WINDOW_MINUTES);
    }

    /**
     * 该行测量值的质量算不算「坏」。
     *
     * <p>契约 §3 的 quality 四个取值里只有 {@code SUSPECT}/{@code FAULT} 是坏；
     * {@code RAW} 是未经处理的原始值、{@code VALID} 是有效值，都算能用的数据。
     * 认不出的取值（含 null）**不算坏**——不认识的串不当成罪证，
     * 与 {@code AlarmConstants.rankOf} 对未知等级取最低同一条保守取向。</p>
     */
    public static boolean isBadQuality(Measurement m) {
        String q = m == null ? null : m.getQuality();
        return q != null && ("SUSPECT".equalsIgnoreCase(q.trim()) || "FAULT".equalsIgnoreCase(q.trim()));
    }

    /**
     * 该行是不是「延迟上报」。
     *
     * <p>两个条件缺一不可：延迟超阈值，**且**采集时间本身还在窗口内（见类注释）。
     * 任一时间为空（设备没给 collectTime 的行不该存在，但防御一下）一律判否。</p>
     */
    public static boolean isDelayed(Measurement m, LocalDateTime now) {
        if (m == null || m.getCollectTime() == null || m.getReceiveTime() == null) {
            return false;
        }
        if (m.getCollectTime().isBefore(windowStart(now))) {
            return false;
        }
        return Duration.between(m.getCollectTime(), m.getReceiveTime()).toMinutes() > DELAY_MINUTES;
    }

    /**
     * 窗口内样本是否够判。不够就**两个判据都不成立**——包括「已有警情该不该解除」，
     * 见 {@code DataQualityMonitor#reconcile} 的说明。
     */
    public static boolean enough(List<Measurement> window) {
        return window != null && window.size() >= MIN_SAMPLES;
    }

    /** 坏质量占比是否达阈值。 */
    public static boolean qualityBad(List<Measurement> window) {
        return enough(window) && ratio(window, null) >= BAD_RATIO;
    }

    /** 延迟样本占比是否达阈值。 */
    public static boolean delayBad(List<Measurement> window, LocalDateTime now) {
        return enough(window) && ratio(window, now) >= BAD_RATIO;
    }

    /** 坏样本占比；{@code now} 为 null 时按质量判坏，否则按延迟判坏。 */
    private static double ratio(List<Measurement> window, LocalDateTime now) {
        int bad = 0;
        for (Measurement m : window) {
            if (now == null ? isBadQuality(m) : isDelayed(m, now)) {
                bad++;
            }
        }
        return (double) bad / window.size();
    }
}
