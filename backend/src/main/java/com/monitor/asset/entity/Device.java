package com.monitor.asset.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.monitor.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 设备档案（毫米波点形变雷达）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("device")
public class Device extends BaseEntity {

    private String code;
    private String name;
    private String type;
    private String serialNo;
    private BigDecimal longitude;
    private BigDecimal latitude;
    /** 数字孪生姿态：WGS84 高程、朝向/俯仰和覆盖参数。 */
    private BigDecimal altitude;
    private BigDecimal headingDegrees;
    private BigDecimal pitchDegrees;
    private BigDecimal detectionRangeM;
    private BigDecimal halfAngleDegrees;
    private BigDecimal antennaHeightM;
    private BigDecimal verticalHalfAngleDegrees;
    /** 显式状态（FAULT 优先），在线/离线由 lastReportTime 推导 */
    private String status;
    private BigDecimal battery;
    private LocalDateTime lastReportTime;
}
