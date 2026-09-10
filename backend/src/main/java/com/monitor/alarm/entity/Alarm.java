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
 * 警情（V1 {@code alarm}）。
 * 状态（D5 冻结）：PENDING / CONFIRMED / PROCESSING / OBSERVING / RESOLVED / FALSE_ALARM。
 */
@Data
@TableName("alarm")
public class Alarm {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long pointId;
    private Long ruleId;
    private String alarmLevel;
    private String status;
    /** 触发时的测项值（快照）。 */
    private BigDecimal triggerValue;
    private LocalDateTime triggeredAt;
    private LocalDateTime resolvedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
