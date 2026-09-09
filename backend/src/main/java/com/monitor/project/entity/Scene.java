package com.monitor.project.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.monitor.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 监测场景（如：灰库 / 库区边坡）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("scene")
public class Scene extends BaseEntity {

    private Long projectId;
    private String name;
    private String type;
    private String description;
}
