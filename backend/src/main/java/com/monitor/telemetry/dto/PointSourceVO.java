package com.monitor.telemetry.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 观测某个测点的来源（设备）及其优先级（V25，复查清单 P1-2）。
 *
 * <p>给两处用：① `/points/{id}/latest` 告诉调用方"这个当前值是谁报的"；
 * ② 多源时前端要能解释"为什么这条曲线 / 这个数来自这台设备"。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PointSourceVO {

    private Long deviceId;
    private String deviceCode;
    private String deviceName;
    /** 数值越小越优先；默认 100（未特别指定）。 */
    private Integer sourcePriority;
    /** 该来源当前是否具备生产能力（ACTIVE + 通视）——权威来源若没标定，值仍会取，但要知道。 */
    private Boolean calibrated;
}
