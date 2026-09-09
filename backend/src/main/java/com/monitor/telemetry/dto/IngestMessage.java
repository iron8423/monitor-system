package com.monitor.telemetry.dto;

import lombok.Data;
import java.util.Map;

/**
 * 单条标准消息（对齐 message-contract）。metrics 为测项->值；position 存角度/距离。
 */
@Data
public class IngestMessage {
    private String schemaVersion;
    private String messageId;
    private String deviceId;
    private String pointCode;
    private String collectTime;   // ISO8601 +08:00
    private String receiveTime;
    private Long sequence;
    private Map<String, Double> metrics;      // defo_mm / rate_mm_d
    private String quality;                    // 提供则用，否则按规则推导
    private Map<String, Object> position;      // angleDeg / distanceM -> attributes
    private Double signal;
    private String state;
}
