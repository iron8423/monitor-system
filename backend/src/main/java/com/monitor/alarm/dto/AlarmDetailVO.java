package com.monitor.alarm.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 警情详情（《B侧接口契约_M0》§4）：快照 + 处置时间线。
 *
 * <p>{@code snapshot} 按 {@code alarmType} 变化：测点警情是「测项 -&gt; 触发值 + 阈值 + 规则」，
 * 设备告警是「成因 + 设备码 + 最后上报时间 + 该成因的判据参数」。</p>
 */
@Data
public class AlarmDetailVO {

    private Long id;
    private String alarmType;
    private Long pointId;
    private String pointCode;
    private Long deviceId;
    private String deviceCode;
    /** DEVICE 类告警的成因：OFFLINE / DATA_QUALITY / DATA_DELAY；POINT 类为空。 */
    private String alarmReason;
    private String level;
    private String status;
    private String triggeredAt;
    private String resolvedAt;
    /** 触发快照：测点警情 {metricCode: 触发值, threshold, operator, ruleName}。 */
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
