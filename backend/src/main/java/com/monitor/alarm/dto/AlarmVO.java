package com.monitor.alarm.dto;

import lombok.Data;

/**
 * 警情列表项（《B侧接口契约_M0》§4）。
 *
 * <p>{@code alarmType} = {@code POINT} 时 {@code pointId/pointCode} 有值；
 * {@code DEVICE} 时 {@code deviceId/deviceCode} 有值、测点侧为空。</p>
 */
@Data
public class AlarmVO {

    private Long id;
    private String alarmType;
    private Long pointId;
    private String pointCode;
    private Long deviceId;
    private String deviceCode;
    private String level;
    private String status;
    private String triggeredAt;
    /** 最近一次处置动作（含系统自动触发/解除），无则空。 */
    private String lastAction;
    private String lastActionAt;
}
