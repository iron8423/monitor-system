package com.monitor.telemetry;

import com.monitor.asset.DeviceStatusPolicy;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接入时钟偏差口径（清单第 10 条）的判据。
 *
 * <p>纯函数，无 mock、无 Spring、不需要 {@code MybatisPlusLambdaCache} 预热——
 * 这正是把容差做成**方法参数**而不是在方法里读常量的原因：边界可以在这里直接打，
 * 而实际生效值来自 {@code @Value} 配置。</p>
 */
class IngestTimePolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 17, 10, 0);
    private static final int TOLERANCE = 300;

    // ---------- tooFarAhead ----------

    @Test
    void acceptsEverythingNotAheadOfNow() {
        assertThat(IngestTimePolicy.tooFarAhead(NOW, NOW, TOLERANCE)).isFalse();
        assertThat(IngestTimePolicy.tooFarAhead(NOW.minusSeconds(1), NOW, TOLERANCE)).isFalse();
        assertThat(IngestTimePolicy.tooFarAhead(NOW.minusYears(1), NOW, TOLERANCE)).isFalse();
    }

    @Test
    void boundaryBelongsToTheAcceptedSide() {
        // 恰好等于容差算「不超」：边界归属写死在一个方向上，免得「恰好 300 秒」
        // 在接入闸门与钳制逻辑里给出两个结论。
        assertThat(IngestTimePolicy.tooFarAhead(NOW.plusSeconds(TOLERANCE), NOW, TOLERANCE)).isFalse();
        assertThat(IngestTimePolicy.tooFarAhead(NOW.plusSeconds(TOLERANCE - 1), NOW, TOLERANCE)).isFalse();
        assertThat(IngestTimePolicy.tooFarAhead(NOW.plusSeconds(TOLERANCE + 1), NOW, TOLERANCE)).isTrue();
    }

    @Test
    void rejectsFarFuture() {
        assertThat(IngestTimePolicy.tooFarAhead(LocalDateTime.of(2099, 1, 1, 0, 0), NOW, TOLERANCE)).isTrue();
    }

    @Test
    void nullOnEitherSideIsNotTooFarAhead() {
        // null 有它自己的原因码（INVALID_COLLECT_TIME），不该在这里被顺手判成未来时间。
        assertThat(IngestTimePolicy.tooFarAhead(null, NOW, TOLERANCE)).isFalse();
        assertThat(IngestTimePolicy.tooFarAhead(NOW.plusYears(1), null, TOLERANCE)).isFalse();
    }

    @Test
    void zeroToleranceStillRejectsAnyAheadTime() {
        // 容差为 0 时，「恰好 now」收、「now+1s」拒。这条挡的是把比较写成 >= 的改法。
        assertThat(IngestTimePolicy.tooFarAhead(NOW, NOW, 0)).isFalse();
        assertThat(IngestTimePolicy.tooFarAhead(NOW.plusSeconds(1), NOW, 0)).isTrue();
    }

    // ---------- clampReceiveTime ----------

    @Test
    void clampKeepsNormalAndPastReceiveTimes() {
        LocalDateTime past = NOW.minusDays(3);
        assertThat(IngestTimePolicy.clampReceiveTime(NOW, NOW, TOLERANCE)).isEqualTo(NOW);
        assertThat(IngestTimePolicy.clampReceiveTime(past, NOW, TOLERANCE)).isEqualTo(past);
        assertThat(IngestTimePolicy.clampReceiveTime(NOW.plusSeconds(TOLERANCE), NOW, TOLERANCE))
                .isEqualTo(NOW.plusSeconds(TOLERANCE));
    }

    @Test
    void clampPullsAheadReceiveTimesDownToNow() {
        assertThat(IngestTimePolicy.clampReceiveTime(NOW.plusSeconds(TOLERANCE + 1), NOW, TOLERANCE))
                .isEqualTo(NOW);
        assertThat(IngestTimePolicy.clampReceiveTime(NOW.plusYears(10), NOW, TOLERANCE))
                .isEqualTo(NOW);
        assertThat(IngestTimePolicy.clampReceiveTime(LocalDateTime.of(2099, 1, 1, 0, 0), NOW, TOLERANCE))
                .isEqualTo(NOW);
    }

    @Test
    void clampPassesNullThrough() {
        // null 表示「报文没带 receiveTime」，由调用方兜成 now()。钳制不该把它变成 now()——
        // 那会让「缺失」和「超前被钳」在日志里长得一样。
        assertThat(IngestTimePolicy.clampReceiveTime(null, NOW, TOLERANCE)).isNull();
        assertThat(IngestTimePolicy.clampReceiveTime(NOW.plusYears(1), null, TOLERANCE))
                .isEqualTo(NOW.plusYears(1));
    }

    // ---------- 可执行的不变式 ----------

    @Test
    void collectToleranceStaysWithinTheOfflineWindow() {
        // javadoc 里那句「与 DeviceStatusPolicy.OFFLINE_MINUTES 同量级」由构建来保证，
        // 而不是靠注释。方向是**不大于**：容差一旦被调过离线判据自己的窗口，
        // 「超前但仍在容差内」的采集时间就比平台认作「数据还新鲜」的时间尺度还长——
        // 那时这条闸门护的东西已经不是「当前值」了。
        assertThat(IngestTimePolicy.MAX_COLLECT_AHEAD_SECONDS)
                .isLessThanOrEqualTo(DeviceStatusPolicy.OFFLINE_MINUTES * 60);
    }

    @Test
    void receiveToleranceBoundsHowLongAheadAHeartbeatCanSurvive() {
        // 钳制后残留的超前量最多是容差本身。把它压在离线窗口之内，最坏情况是
        // 在线状态被多钉一个窗口（而不是无限期）——这正是清单第 10 条
        // 「避免影响在线状态」在取了容差之后还能成立的边界。
        assertThat(IngestTimePolicy.MAX_RECEIVE_AHEAD_SECONDS)
                .isLessThanOrEqualTo(DeviceStatusPolicy.OFFLINE_MINUTES * 60);
    }
}
