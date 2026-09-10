package com.monitor.alarm.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 警情详情（《B侧接口契约_M0》§4）：快照 + 处置时间线。
 */
@Data
public class AlarmDetailVO {

    private Long id;
    private Long pointId;
    private String pointCode;
    private String level;
    private String status;
    private String triggeredAt;
    private String resolvedAt;
    /** 触发快照：{metricCode: 触发值, threshold, operator, ruleName}。 */
    private Map<String, Object> snapshot;
    private List<TimelineItem> timeline;

    /** 时间线一项，对应一行 {@code alarm_action}。 */
    @Data
    public static class TimelineItem {

        private String time;
        private String action;
        private String operator;
        private String comment;
    }
}
