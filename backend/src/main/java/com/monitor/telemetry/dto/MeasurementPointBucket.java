package com.monitor.telemetry.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 批量分桶聚合的一行（P2-4）：比 {@link MeasurementBucket} 多一个 {@code pointId}。
 *
 * <p>为什么不给 {@code MeasurementBucket} 加一列：单点版本的 SQL 里根本没有 {@code point_id}
 * 这一列（{@code GROUP BY} 也不含它），加一个永远为 null 的字段会让"这个值为什么是空的"
 * 变成一个每次读代码都要重新推一遍的问题。</p>
 */
@Data
public class MeasurementPointBucket {

    private Long pointId;
    private LocalDateTime bucketTime;
    private BigDecimal bucketValue;
}
