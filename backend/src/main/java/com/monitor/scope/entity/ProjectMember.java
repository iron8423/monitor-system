package com.monitor.scope.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.monitor.common.base.Identifiable;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目成员：用户 ↔ 项目（V8）。数据范围的唯一依据——「你能看到什么」= 你在哪些项目里。
 *
 * <p>硬删除（无 {@code deleted}），与 {@code device_point} 一致：唯一约束
 * {@code (user_id, project_id)} 与软删除冲突（软删后再加回来会撞唯一键），
 * 而移除成员本就该是幂等的增删。</p>
 *
 * <p>刻意不用 {@code sys_user.organization_id}：一个组织可下有多个项目，粒度不同；
 * 且种子是 1 组织 1 项目，组织级隔离在那个种子上演示不出来。</p>
 */
@Data
@TableName("project_member")
public class ProjectMember implements Identifiable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long projectId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
