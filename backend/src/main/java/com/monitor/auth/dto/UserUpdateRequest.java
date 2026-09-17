package com.monitor.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员修改用户资料（不含口令）。
 *
 * <p><b>没有 username 字段是刻意的</b>：账号是登录名，也是审计与历史记录里的身份标识，
 * 改掉它等于把「谁做过什么」这条线索换了个名字。要换账号就新建一个、停用旧的。</p>
 *
 * <p>也没有 password：改口令走单独的重置入口，以免「保存资料」这种日常操作顺手改掉别人的口令。</p>
 */
@Data
public class UserUpdateRequest {

    @NotBlank(message = "请输入姓名")
    @Size(max = 64, message = "姓名过长")
    private String displayName;

    @NotBlank(message = "请选择角色")
    private String role;

    private Long organizationId;

    @Size(max = 32, message = "联系电话过长")
    private String phone;

    @Email(message = "邮箱格式不正确")
    @Size(max = 128, message = "邮箱过长")
    private String email;

    @Size(max = 64, message = "岗位过长")
    private String jobTitle;

    private Boolean enabled;
}
