package com.monitor.alarm.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 告警规则新增/修改请求。
 *
 * <p>作用域（V22）：{@code pointId} 非空 = 该测点专属；否则 {@code projectId} 非空 = 该项目
 * 下所有测点；两者都空 = 全局规则。同时给出且互相矛盾（测点不属于该项目）时 400，
 * 不做静默纠正——静默纠正会让调用方以为规则建在了 A 上。</p>
 */
@Data
public class AlarmRuleRequest {

    private String name;
    private Long pointId;
    /** 项目作用域；{@code pointId} 为空时生效。 */
    private Long projectId;
    private String metricCode;
    /** THRESHOLD / RATE / CHANGE */
    private String type;
    /** gte / lte */
    private String operator;
    private BigDecimal value;
    private Integer windowMinutes;
    /** notice / warning / alarm */
    private String level;
    private BigDecimal recoveryValue;
    private Integer repeatSuppressSeconds;
    private Boolean enabled;
}
