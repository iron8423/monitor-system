package com.monitor.asset.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 设备状态视图：在线/离线由最近上报时间推导，低电量按阈值判断。
 */
@Data
public class DeviceStatusVO {

    private Long deviceId;
    private String code;
    private String status;
    private boolean online;
    private BigDecimal battery;
    private boolean lowBattery;
    private LocalDateTime lastReportTime;
}
