package com.monitor.telemetry;

import com.monitor.asset.DeviceStatusPolicy;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 接入时间的时钟偏差口径（清单第 10 条）。纯静态、不依赖 Spring，与
 * {@link DataQualityPolicy} 同构。
 *
 * <p><b>容差为什么是 300 秒（5 分钟）</b>：取自 {@link DeviceStatusPolicy#OFFLINE_MINUTES}
 * ——一个超前平台时间 5 分钟以上的采集时间，描述不了平台此刻认作「现在」的任何东西，
 * 因此它不能成为「当前值」；同时 5 分钟远大于任何合理的网关缓冲与网络时延，
 * 正常数据不会被误伤。方向是**不大于**离线窗口（{@code IngestTimePolicyTest} 把它变成了会红的
 * 断言）：容差一旦调过那个窗口，「超前但仍在容差内」的时间戳就比平台认作「数据还新鲜」
 * 的时间尺度还长，这条闸门护的就不再是「当前值」了。</p>
 *
 * <p><b>时区前提</b>：本类比较的是两个 {@link LocalDateTime}，隐含前提是它们同处一个时区。
 * 进入接入路径的 collectTime 已经由 {@code Times.parse} 归一化到 {@code Times.ZONE}
 * （Asia/Shanghai），而参照的 now 来自 {@code LocalDateTime.now()}，取 JVM 默认时区。
 * 两者在生产容器里一致（{@code backend/Dockerfile} 有 {@code ENV TZ=Asia/Shanghai}），
 * 在开发机上取决于宿主时区。若哪一天这个前提破了，症状是**闸门对全部数据开火**——
 * 响亮的失败，不是静默的，这是可接受的失败方向。</p>
 */
public final class IngestTimePolicy {

    /** 采集时间超前平台时间的容忍秒数；超过即拒收（见 {@code IngestService#basicError}）。 */
    public static final int MAX_COLLECT_AHEAD_SECONDS = 300;

    /**
     * 接收时间超前平台时间的容忍秒数；超过则**钳制**而不是拒收
     * （{@code receiveTime} 是平台自己的字段，见 {@code IngestService} 里的说明）。
     *
     * <p>它同时是钳制后**残留超前量的上界**：一个被钳过的 {@code receiveTime} 最多只领先
     * 平台这么久。而 {@code DeviceStatusPolicy} 判在线用的正是这个值，所以容差压在离线
     * 窗口之内，最坏情况是「在线状态被多钉一个窗口」，而不是无限期。</p>
     */
    public static final int MAX_RECEIVE_AHEAD_SECONDS = 300;

    private IngestTimePolicy() {
    }

    /**
     * 该时间是否超前 {@code now} 超过容差。
     *
     * <p>容差刻意作为**参数**传入而不是在方法里读常量：这样纯单测能直接打边界，
     * 而实际生效值来自 {@code @Value} 配置，本类的常量只是有据可依的默认值。</p>
     *
     * <p>恰好等于容差算**不超**（{@code Duration.compareTo} 用严格大于）：
     * 边界归属写死在一个方向上，免得「恰好 300 秒」在不同地方给出两个结论。</p>
     */
    public static boolean tooFarAhead(LocalDateTime t, LocalDateTime now, int toleranceSeconds) {
        if (t == null || now == null) return false;
        return Duration.between(now, t).getSeconds() > toleranceSeconds;
    }

    /**
     * 把超前的接收时间钳到 {@code now}；没超前则原样返回。
     *
     * <p>钳制而不是拒收：{@code receiveTime} 是**平台自己的字段**（契约里写明
     * 「平台接收时间」，且是唯一允许缺省取当前时间的字段）。因为一个头部字段写错就丢掉一条
     * 真实测量，等于丢掉平台本身就是权威的那份数据——把平台的真相盖上去更对。</p>
     *
     * <p>只钳上界，不钳下界：平台时间落后于设备时间是不可能的（平台自己盖的章），
     * 若真的出现，多半是设备时钟偏快，在容差内且无害。</p>
     */
    public static LocalDateTime clampReceiveTime(LocalDateTime receiveAt, LocalDateTime now,
                                                 int toleranceSeconds) {
        if (receiveAt == null || now == null) return receiveAt;
        return tooFarAhead(receiveAt, now, toleranceSeconds) ? now : receiveAt;
    }
}
