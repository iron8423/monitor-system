package com.monitor.telemetry.service;

import com.monitor.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * series 时间窗口径（复查清单 P0-3）的纯函数判据。
 *
 * <p>这块的性质与 {@code IngestTimePolicy} 同类：**没有它，一次不传时间窗的调用就会
 * 把该测点全部历史拉进内存**（生产基线 1000 点 × 5 秒）。判据做成纯函数，
 * 边界才能在这里直接打——尤其是"恰好 31 天"和"31 天零 1 秒"这两侧。</p>
 */
class SeriesWindowPolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 18, 12, 0);

    @Test
    void bothMissingFallsBackToLast24Hours() {
        SeriesWindowPolicy.Window w = SeriesWindowPolicy.resolve(null, null, NOW);

        assertThat(w.from()).isEqualTo(NOW.minusHours(24));
        assertThat(w.to()).isEqualTo(NOW);
        assertThat(w.fromDefaulted()).isTrue();
        assertThat(w.toDefaulted()).isTrue();
    }

    @Test
    void onlyFromUsesNowAsUpperBound() {
        LocalDateTime from = NOW.minusHours(6);

        SeriesWindowPolicy.Window w = SeriesWindowPolicy.resolve(from, null, NOW);

        assertThat(w.from()).isEqualTo(from);
        assertThat(w.to()).isEqualTo(NOW);
        assertThat(w.fromDefaulted()).isFalse();
        assertThat(w.toDefaulted()).isTrue();
    }

    /** 只给 to 时，下界贴着 to 往前 24 小时——而不是从 1970 年开始拉全史。 */
    @Test
    void onlyToUses24HoursBeforeIt() {
        LocalDateTime to = NOW.minusDays(3);

        SeriesWindowPolicy.Window w = SeriesWindowPolicy.resolve(null, to, NOW);

        assertThat(w.from()).isEqualTo(to.minusHours(24));
        assertThat(w.to()).isEqualTo(to);
        assertThat(w.fromDefaulted()).isTrue();
        assertThat(w.toDefaulted()).isFalse();
    }

    @Test
    void explicitWindowIsKeptAsIs() {
        LocalDateTime from = NOW.minusDays(7);
        LocalDateTime to = NOW.minusHours(1);

        SeriesWindowPolicy.Window w = SeriesWindowPolicy.resolve(from, to, NOW);

        assertThat(w.from()).isEqualTo(from);
        assertThat(w.to()).isEqualTo(to);
        assertThat(w.fromDefaulted()).isFalse();
        assertThat(w.toDefaulted()).isFalse();
    }

    /** 边界归属：恰好 31 天放行，31 天零 1 秒拒绝——两侧都要钉住，否则边界迟早漂移。 */
    @Test
    void maxSpanBoundaryBelongsToTheAcceptedSide() {
        LocalDateTime from = NOW.minusDays(31);
        assertThat(SeriesWindowPolicy.resolve(from, NOW, NOW).from()).isEqualTo(from);

        assertThatThrownBy(() -> SeriesWindowPolicy.resolve(from.minusSeconds(1), NOW, NOW))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("31 天");
    }

    @Test
    void reversedWindowIsRejected() {
        assertThatThrownBy(() -> SeriesWindowPolicy.resolve(NOW, NOW.minusHours(1), NOW))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("from 不能晚于 to");
    }

    /** 上下界相同（单点窗口）合法：那不是"空窗口"，而是一次合法的一瞬查询。 */
    @Test
    void zeroLengthWindowIsAllowed() {
        SeriesWindowPolicy.Window w = SeriesWindowPolicy.resolve(NOW, NOW, NOW);

        assertThat(w.from()).isEqualTo(w.to());
    }
}
