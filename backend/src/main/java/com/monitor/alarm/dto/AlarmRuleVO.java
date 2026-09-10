package com.monitor.alarm.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 告警规则输出（《B侧接口契约_M0》§4）：对外用 type / value / level 命名，库内为 rule_type / threshold_value / alarm_level。
 */
@Data
public class AlarmRuleVO {

    private Long id;
    private String name;
    private Long pointId;
    private String pointCode;
    private String metricCode;
    private String type;
    private String operator;
    private BigDecimal value;
    private Integer windowMinutes;
    private String level;
    private BigDecimal recoveryValue;
    private Integer repeatSuppressSeconds;
    private Boolean enabled;
}
