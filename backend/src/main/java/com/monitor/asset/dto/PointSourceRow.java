package com.monitor.asset.dto;

import lombok.Data;

/**
 * 「某个测点由哪些设备观测」的查询行（V25，复查清单 P1-2）。
 *
 * <p>{@code measurement.device_id} 存的是设备**编码**（VARCHAR），而 {@code device_point.device_id}
 * 是数字主键——两者同名不同物。这个投影把编码一起查出来，免得调用点各自再拼一次 JOIN
 * （本仓在数据可信度判据那里已经因为这两个字段混用踩过一次）。</p>
 */
@Data
public class PointSourceRow {

    private Long pointId;
    private Long deviceId;
    private String deviceCode;
    private String deviceName;
    /** 数值越小越优先；默认 100。 */
    private Integer sourcePriority;
    private String calibrationStatus;
    private Boolean lineOfSight;
}
