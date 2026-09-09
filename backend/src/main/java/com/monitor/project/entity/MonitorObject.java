package com.monitor.project.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.monitor.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 监测对象（层次：项目 → 场景 → 对象 → 测点）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("monitor_object")
public class MonitorObject extends BaseEntity {

    private Long sceneId;
    private String name;
    private String type;
    private String description;
}
