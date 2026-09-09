package com.monitor.asset.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.monitor.common.base.Identifiable;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 设备维护记录（运维闭环 D8）。
 */
@Data
@TableName("maintenance_record")
public class MaintenanceRecord implements Identifiable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long deviceId;
    private String type;
    private String description;
    private String operator;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
