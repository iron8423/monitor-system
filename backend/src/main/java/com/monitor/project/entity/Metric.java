package com.monitor.project.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.monitor.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 测项。雷达点形变每点 2 项：defo_mm(累计形变 mm) + rate_mm_d(形变速率 mm/d)。
 * 口径见 docs/message-contract.md（M0-D1 定案：无 X/Y/Z 三分量）。
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
