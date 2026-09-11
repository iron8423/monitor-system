package com.monitor.alarm.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 告警规则新增/修改请求。{@code pointId} 为空表示全局规则。
 */
@Data
public class AlarmRuleRequest {

    private String name;
    private Long pointId;
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
