package com.monitor.alarm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 告警规则（V1 {@code alarm_rule}，D6 字段集）。
 * {@code pointId} 为空表示全局规则（作用于所有测点）。
 * 枚举：ruleType THRESHOLD/RATE/CHANGE；operator gte/lte；alarmLevel notice/warning/alarm。
 */
@Data
@TableName("alarm_rule")
public class AlarmRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private Long pointId;
    private String metricCode;
    private String ruleType;
    private String operator;
    private BigDecimal thresholdValue;
    private Integer windowMinutes;
    private BigDecimal recoveryValue;
    private String alarmLevel;
    private Integer repeatSuppressSeconds;
    private Boolean enabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
