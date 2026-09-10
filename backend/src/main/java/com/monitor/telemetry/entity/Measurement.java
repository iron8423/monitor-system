package com.monitor.telemetry.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 测量值（按测项一行；一条消息含 N 测项 -> 拆 N 行，共用 message_id）。
 * 列名对齐 A 的 measurement 表（V1，2026-09-10 对表）：measure_value（非 value）、point_id 与 point_code 并存。
 */
@Data
@TableName("measurement")
public class Measurement {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String messageId;
    private String deviceId;
    private Long pointId;
    private String pointCode;
    private String metricCode;
    private LocalDateTime collectTime;
    private LocalDateTime receiveTime;
    private Double measureValue;
    private String quality;
    private String attributes;   // JSON <=1024（position/signal/state）
    private String rawRef;
}
