package com.monitor.alarm.dto;

import lombok.Data;

/**
 * 警情列表项（《B侧接口契约_M0》§4）。
 *
 * <p>{@code alarmType} = {@code POINT} 时 {@code pointId/pointCode} 有值；
 * {@code DEVICE} 时 {@code deviceId/deviceCode} 有值、测点侧为空。</p>
 *
 * <p>{@code alarmReason}（V7 起）只对 {@code DEVICE} 有意义：
 * {@code OFFLINE} / {@code DATA_QUALITY} / {@code DATA_DELAY}。列表里几台设备的告警
 * 长得一模一样（都是 {@code deviceCode} + {@code notice}），不给成因的话前端无法区分
 * 「掉线了」和「在报数但数据不可信」——而这两种的处置动作完全不同。</p>
 */
@Data
public class AlarmVO {

    private Long id;
    private String alarmType;
    private Long pointId;
    private String pointCode;
    private Long deviceId;
    private String deviceCode;
    /** DEVICE 类告警的成因；POINT 类为空。 */
    private String alarmReason;
    private String level;
    private String status;
    private String triggeredAt;
    /** 最近一次处置动作（含系统自动触发/解除），无则空。 */
    private String lastAction;
    private String lastActionAt;
}
