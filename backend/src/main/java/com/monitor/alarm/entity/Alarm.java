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
 *
 * <p>{@code alarmReason}（V7 起）细分 {@code DEVICE} 类的成因：
 * {@code OFFLINE} / {@code DATA_QUALITY} / {@code DATA_DELAY}，见 {@code AlarmConstants}。
 * 它**必须落库**而不是读时推导——成因是「当时为什么开这条警情」的历史事实，
 * 设备掉线后不该把一条早先因数据质量开的警情重新解说成离线。
 * {@code POINT} 类警情不适用，恒为 null。</p>
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
    /** 设备警情的成因（V7）：OFFLINE / DATA_QUALITY / DATA_DELAY；测点警情为 null。 */
    private String alarmReason;
    private Long ruleId;
    /**
     * 触发时所属规则的测项**副本**（V14 起落库）。
     * <p>为什么要在警情上再存一份：未解除唯一性要的是「测点 + **测项**」，而此前测项只能靠
     * {@code ruleId} 间接表达（{@code findOpen} 传的就是「该测项的规则集」）。升级功能
     * （V4 的种子规则）恰恰依赖同一测项下有多条规则，所以不能对 {@code (pointId, ruleId)}
     * 建唯一键。落库而不读时推导：规则可以被改、可以被删，警情记录的是**当时**为什么开。</p>
     */
    private String metricCode;
    /**
     * 未解除唯一键（V14，见 {@code V14__alarm_open_key.sql}）：未解除时为
     * {@code 'P:<pointId>:<metricCode>'}（测点警情）或 {@code 'D:<deviceId>:<reason>'}（设备警情），
     * 进入终态时置回 null。
     * <p><b>不要用 {@code updateById} 关闭警情</b>：MyBatis-Plus 跳过 null 字段，写不出
     * {@code open_key = NULL}，那条警情会永久占住键位，把该测点该测项后续所有警情挡在门外。
     * 关闭统一走 {@code AlarmMapper.closeAlarm}。</p>
     */
    private String openKey;
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
