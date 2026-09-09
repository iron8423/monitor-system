package com.monitor.telemetry.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 测量值（按测项一行；一条消息含 N 测项 -> 拆 N 行，共用 message_id）。
 * ⚠️ 列名/字段需与 A 实际 measurement 表一致（对表后确认）。
 */
@Data
@TableName("measurement")
public class Measurement {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String messageId;
    private String deviceId;
    private String pointCode;
    private String metricCode;
    private LocalDateTime collectTime;
    private LocalDateTime receiveTime;
    private Double value;
    private String quality;
    private String attributes;   // JSON <=1024（position/signal/state）
    private String rawRef;
}
