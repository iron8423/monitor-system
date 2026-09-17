package com.monitor.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 本人修改自己的资料（「个人中心」）。
 *
 * <p>与 {@code UserUpdateRequest}（管理员改别人的**权限**）刻意分开：这里只有个人信息，
 * **没有 role / enabled**——本人不能给自己提权，也不该能改自己的启用状态。</p>
 */
@Data
public class ProfileUpdateRequest {

    @NotBlank(message = "请输入姓名")
    @Size(max = 64, message = "姓名过长")
    private String displayName;

    @Size(max = 128, message = "公司名过长")
    private String company;

    @Size(max = 64, message = "岗位过长")
    private String jobTitle;

    @Size(max = 32, message = "联系电话过长")
    private String phone;

    @Email(message = "邮箱格式不正确")
    @Size(max = 128, message = "邮箱过长")
    private String email;
}
