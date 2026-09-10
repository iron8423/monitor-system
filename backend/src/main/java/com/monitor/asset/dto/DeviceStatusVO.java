package com.monitor.asset.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 设备状态视图：在线/离线由最近上报时间推导，低电量按阈值判断。
 */
@Data
public class DeviceStatusVO {

    private Long deviceId;
    private String code;
    private String status;
    private boolean online;
    private BigDecimal battery;
    private boolean lowBattery;

    /**
     * ISO8601 带时区（契约 §0）。{@code String} 而非 {@code LocalDateTime}：
     * 裸时间字段会被 Jackson 按 ISO 序列化成 {@code 2026-08-27T15:05:00}——**不带偏移**，
     * 前端无从判断时区；全仓其余时间字段都走 {@code Times.iso} 出 {@code +08:00}，此处对齐。
     */
    private String lastReportTime;
}
