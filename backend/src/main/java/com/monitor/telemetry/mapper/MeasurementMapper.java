package com.monitor.telemetry.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.telemetry.entity.Measurement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MeasurementMapper extends BaseMapper<Measurement> {

    /** 幂等：device_id + message_id 是否已存在。 */
    @Select("SELECT COUNT(*) FROM measurement WHERE device_id = #{deviceId} AND message_id = #{messageId}")
    long countByMessageId(String deviceId, String messageId);

    /**
     * 某台设备在窗口内**收到**的测量值（数据可信度判定用）。
     *
     * <p>{@code deviceId} 传的是**设备码**（{@code device.code}）不是数字主键——
     * {@code measurement.device_id} 是 {@code VARCHAR(64)}、存的是上报报文里那个设备码，
     * 而 {@code device_point.device_id} 才是 {@code BIGINT}。同名不同物，混用会静默查出空集
     * （不报错、只是判据永远不成立），是最容易写错又最难发现的一处。</p>
     *
     * <p>窗口卡在 {@code receive_time}（平台侧收到的时间）而不是 {@code collect_time}：
     * 「最近收到的数据」才是这条链路的当下状态；按采集时间卡的话，
     * 一台回补历史数据的设备会看起来「最近什么都没收到」。</p>
     */
    @SuppressWarnings("null")
    default List<Measurement> recentOfDevice(String deviceCode, LocalDateTime since) {
        return selectList(new LambdaQueryWrapper<Measurement>()
                .eq(Measurement::getDeviceId, deviceCode)
                .ge(Measurement::getReceiveTime, since));
    }

    /**
     * 「取某测点最新一行」的**唯一**查询口径：{@code collect_time DESC, id DESC}。
     *
     * <p>{@code collect_time} 相同时必须再有确定的次序。同一点在同一采集时刻落两行是正常的——
     * 幂等键是 {@code device_id + message_id}，不含 {@code collect_time}，所以设备重传换了 messageId、
     * 或两台设备覆盖同一点，都会留下同刻的两行，这不是「重复上报」。此时只按 {@code collect_time}
     * 排序的话，取到哪一行由**数据库返回顺序**决定，而数据库返回顺序取决于执行计划：实测同一个库，
     * 走索引反向扫时恰好先给到高 id（于是看着一切正常），一旦退化成全表扫（小表、库刚建好、
     * H2 内存库）就先给到先写的那行——同一份代码在两个计划下给出两个答案。</p>
     *
     * <p>本方法把排序收在一处：{@code MeasurementQueryService#latest}（最新测项）与
     * {@code ProjectSummaryService#maxDeformation}（最新形变）曾经各写各的排序，一处带 id 兜底、
     * 一处不带，于是同一测点在 {@code /points/{id}/latest} 与 {@code /projects/{id}/summary}
     * 上给出两个值。加筛选条件请在此之后调 {@code .eq(...)} 之前想清楚顺序——
     * MyBatis-Plus 按调用顺序拼 SQL，{@code orderBy} 之后再加条件会拼到 ORDER BY 后面。</p>
     *
     * <p>兜底用 {@code id} 而不是 {@code receive_time}：主键单调递增，等价于「后写库的覆盖先写的」，
     * 且不像 {@code receive_time} 那样有 NULL 排序的方言差异（PG 的 DESC 把 NULL 排最前、H2 排最后）。</p>
     *
     * @param metricCode 只取该测项；传 {@code null} 表示不限测项
     */
    @SuppressWarnings("null")
    default LambdaQueryWrapper<Measurement> latestRowOf(Long pointId, String metricCode) {
        return new LambdaQueryWrapper<Measurement>()
                .eq(Measurement::getPointId, pointId)
                .eq(metricCode != null, Measurement::getMetricCode, metricCode)
                .isNotNull(Measurement::getCollectTime)
                .orderByDesc(Measurement::getCollectTime)
                .orderByDesc(Measurement::getId);
    }
}
