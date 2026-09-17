package com.monitor.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 自助注册。
 *
 * <p>个人信息**由本人在注册时填写**（姓名 / 公司 / 岗位 / 联系电话 / 邮箱），
 * 注册后可在「个人中心」自行修改——管理员不参与这些字段（管理员只负责看与删，
 * 见 {@code UserController}）。</p>
 *
 * <p><b>role 只允许非管理员</b>：自助注册若能选 ADMIN，等于任何人都能把自己提成管理员。
 * 需要管理员权限的账号由既有管理员在用户管理里改角色（那是权限配置，不是个人信息）。</p>
 */
@Data
public class RegisterRequest {

    @NotBlank(message = "请输入账号")
    @Pattern(regexp = "[A-Za-z0-9_.-]{3,32}", message = "账号只能用字母、数字、下划线、点或短横线，长度 3–32")
    private String username;

    @NotBlank(message = "请输入密码")
    @Size(min = 8, max = 64, message = "密码长度需为 8–64 位")
    private String password;

    @NotBlank(message = "请输入姓名")
    @Size(max = 64, message = "姓名过长")
    private String displayName;

    /** 公司 / 单位名（自由文本，平台按名字找或建一个「组织」，见 AuthService#organizationOf） */
    @Size(max = 128, message = "公司名过长")
    private String company;

    @Size(max = 64, message = "岗位过长")
    private String jobTitle;

    @Size(max = 32, message = "联系电话过长")
    private String phone;

    @Email(message = "邮箱格式不正确")
    @Size(max = 128, message = "邮箱过长")
    private String email;

    @NotBlank(message = "请选择角色")
    private String role;
}
