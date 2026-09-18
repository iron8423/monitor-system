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
    /**
     * 变更前 / 变更后的整行快照（V23，P1-8）。JSON 字符串，超过 2048 会被切面截断并标记；
     * 新建时 {@code beforeJson} 为空、删除时 {@code afterJson} 为空——两者都不是"漏记"，
     * 而是这件事本来就没有那一端。
     */
    private String beforeJson;
    private String afterJson;
    /** SUCCESS / FAILED；旧数据在 V23 里回填成 SUCCESS（它们本来就是成功后才写的）。 */
    private String result;
    /** FAILED 时的原因（业务异常 message），便于回答"为什么被拒"。 */
    private String errorMessage;
    private String ip;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
