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
 * 警情（V1 {@code alarm}，V3 起支持设备告警）。
 * 状态（D5 冻结）：PENDING / CONFIRMED / PROCESSING / OBSERVING / RESOLVED / FALSE_ALARM。
 *
 * <p>{@code alarmType} 区分来源：{@code POINT} 挂测点（{@code pointId} 必填）、
 * {@code DEVICE} 挂设备（{@code deviceId} 必填、{@code pointId} 为空）。两类共用同一张表、
 * 同一套状态机与处置管线，列表 / 详情 / 处置 / 时间线 / SSE 都不必分叉。</p>
 */
@Data
@TableName("alarm")
public class Alarm {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** POINT（测点形变警情）/ DEVICE（设备状态告警），见 {@code AlarmConstants}。 */
    private String alarmType;
    /** 测点警情的挂靠点；设备警情为 null。 */
    private Long pointId;
    /** 设备警情的挂靠设备；测点警情为 null。 */
    private Long deviceId;
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
