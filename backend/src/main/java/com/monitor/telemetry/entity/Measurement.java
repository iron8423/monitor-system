package com.monitor.telemetry.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 测量值（对齐 A 的 measurement 表：point_id / metric_id / measure_value / seq / schema_version / unit）。
 * 一条消息含 N 测项 -> 拆 N 行，共用 message_id；幂等键 device_id + message_id（表唯一约束）。
 */
@Data
@TableName("measurement")
public class Measurement {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long pointId;          // point_id（FK -> monitor_point.id）
    private Long metricId;         // metric_id（FK -> metric.id）
    private String metricCode;     // defo_mm / rate_mm_d
    private String deviceId;       // 设备编码（radar-001）
    private String messageId;      // 幂等键之一
    private Long seq;              // 设备侧序号
    private String schemaVersion;  // 1.0
    private BigDecimal measureValue;  // measure_value
    private String unit;
    private String quality;
    private String attributes;     // JSON（position/signal/state）
    private String rawRef;
    private LocalDateTime collectTime;
    private LocalDateTime receiveTime;
    private LocalDateTime createdAt;
}
