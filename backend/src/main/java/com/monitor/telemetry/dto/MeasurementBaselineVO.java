package com.monitor.telemetry.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 测量基准输出（P1-4）。时间口径与全仓一致：ISO8601 带 +08:00（见 {@code Times}）。
 */
@Data
public class MeasurementBaselineVO {

    private Long id;
    private Long pointId;
    /** 生效时刻（ISO8601 带时区）。 */
    private String effectiveFrom;
    /** 原因短码 + 中文标签：界面直接显示标签，接口调用方按短码判断（别按中文匹配）。 */
    private String reason;
    private String reasonLabel;
    private String note;
    /** 换基准时的读数（mm）；当时该点没有数据时为 null。 */
    private BigDecimal baselineValueMm;
    private String operator;
    private String createdAt;
}
