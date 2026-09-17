package com.monitor.auth.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统用户（4 个演示角色：ADMIN/OPERATOR/ANALYST/MAINTAINER）。
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** bcrypt 编码后的密码，序列化时不外泄 */
    private String password;

    private String displayName;

    private String role;

    private Long organizationId;

    /** 个人资料（V17）：联系电话。空表示未登记，展示层给「—」而不是空串。 */
    private String phone;

    /** 个人资料（V17）：邮箱。 */
    private String email;

    /**
     * 个人资料（V17）：岗位 / 职位。
     *
     * <p>与 {@code role} 是两件事：{@code role} 决定权限（ADMIN/OPERATOR/…），
     * 岗位是给人看的组织称谓（如「监测室主任」）。一个 OPERATOR 可以是「值班长」，
     * 也可以只是「值班员」——权限一样，岗位不同。</p>
     */
    private String jobTitle;

    private Boolean enabled;

    /**
     * 令牌版本：JWT 里带签发时的值，鉴权时与这里比对，不等即视为已失效。
     *
     * <p>递增它就作废该用户此前签发的全部令牌（停用/降权/改密/登出）。不外泄给前端
     * ——{@code UserVO.from} 不含本字段。</p>
     */
    private Integer tokenVersion;

    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
