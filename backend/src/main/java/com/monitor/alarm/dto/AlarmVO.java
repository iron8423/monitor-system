package com.monitor.alarm.dto;

import lombok.Data;

/**
 * 警情列表项（《B侧接口契约_M0》§4）。
 */
@Data
public class AlarmVO {

    private Long id;
    private Long pointId;
    private String pointCode;
    private String level;
    private String status;
    private String triggeredAt;
    /** 最近一次处置动作（含系统自动触发/解除），无则空。 */
    private String lastAction;
    private String lastActionAt;
}
