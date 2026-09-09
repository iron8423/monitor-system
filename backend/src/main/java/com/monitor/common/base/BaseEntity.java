package com.monitor.common.base;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 实体基类：主键自增 + 软删除标记 + 创建/更新时间（自动填充）。
 * 全局配置 {@code mybatis-plus.global-config.db-config.logic-delete-field: deleted} 使其走逻辑删除。
 */
@Data
public abstract class BaseEntity implements Identifiable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 逻辑删除标记：0=正常 1=删除 */
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
