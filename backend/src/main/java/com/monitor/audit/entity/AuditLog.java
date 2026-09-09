package com.monitor.audit.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.monitor.common.base.Identifiable;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志（不可变，硬删除不适用）。
 */
@Data
@TableName("audit_log")
public class AuditLog implements Identifiable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String username;
    private String action;
    private String targetType;
    private String targetId;
    private String detail;
    private String ip;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
