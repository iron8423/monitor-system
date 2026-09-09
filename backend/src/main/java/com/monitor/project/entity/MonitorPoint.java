package com.monitor.project.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.monitor.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 监测测点（灰库 3 点 + 边坡 4 点，共 7 点）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("monitor_point")
public class MonitorPoint extends BaseEntity {

    private Long objectId;
    private String code;
    private String name;
    private String type;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private BigDecimal altitude;
    private Boolean enabled;
}
