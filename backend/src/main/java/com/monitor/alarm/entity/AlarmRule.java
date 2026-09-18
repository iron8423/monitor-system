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
 *
 * <p>作用域三档（V22 起，判定见 {@code AlarmEngine#enabledRulesFor}）：</p>
 * <ol>
 *   <li>{@code pointId} 非空 —— 该测点专属（{@code projectId} 只作档案，不参与匹配）；</li>
 *   <li>{@code pointId} 空、{@code projectId} 非空 —— 该项目下所有测点；</li>
 *   <li>两者都空 —— 全局规则（V22 之前的种子形状，保留兼容）。</li>
 * </ol>
 *
 * <p>「全局」在 V20 之后是**危险默认值**：一套阈值会同时套到桥梁、路基与储罐基础上，
 * 阈值只对上了量纲、没对上结构。管理端默认按项目建规则，全局档留给"确实全库同口径"的场合。</p>
 *
 * 枚举：ruleType THRESHOLD/RATE/CHANGE；operator gte/lte；alarmLevel notice/warning/alarm。
 */
@Data
@TableName("alarm_rule")
public class AlarmRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private Long pointId;
    /** 项目作用域（V22）：{@code pointId} 为空时按本项目匹配；两者都空 = 全局。 */
    private Long projectId;
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
