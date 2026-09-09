package com.monitor.project.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.monitor.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 测项（累计形变 X/Y/Z、合位移、速率）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("metric")
public class Metric extends BaseEntity {

    private Long pointId;
    private String code;
    private String name;
    private String unit;
    private Integer sortOrder;
}
