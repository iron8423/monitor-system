package com.monitor.telemetry.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 分桶聚合的一行（hour/day 粒度）：{@code DATE_TRUNC} 出来的桶起点 + 桶内均值。
 *
 * <p>只是 SQL 与 Java 之间的搬运工：把分桶下沉到数据库（P0-3），
 * 才不会为了画 744 个桶把 50 万行原始数据搬进 JVM 再求平均。</p>
 */
@Data
public class MeasurementBucket {

    /** 桶起点（本地时间，与 {@code measurement.collect_time} 同口径，无时区换算）。 */
    private LocalDateTime bucketTime;

    /** 桶内均值；桶内全为 NULL 时数据库给 NULL。 */
    private BigDecimal bucketValue;
}
