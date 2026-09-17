package com.monitor.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员新建账号。
 *
 * <p>口令在这里是**必需**的（≥ 8 位）：管理员建号时就得定一个初始口令交给本人，
 * 再由本人到「个人中心」自行修改——系统目前没有邮件/短信通道，不做「邀请链接」那一套。
 * 长度下限与自助改密一致（见 {@code ChangePasswordRequest}）。</p>
 */
@Data
public class UserCreateRequest {

    @NotBlank(message = "请输入账号")
    @Pattern(regexp = "[A-Za-z0-9_.-]{3,32}", message = "账号只能用字母、数字、下划线、点或短横线，长度 3–32")
    private String username;

    @NotBlank(message = "请输入初始密码")
    @Size(min = 8, max = 64, message = "初始密码长度需为 8–64 位")
    private String password;

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
