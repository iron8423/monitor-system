package com.monitor.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 管理员修改用户的**权限**：角色与启用状态。
 *
 * <p>只有两个字段，是刻意的（2026-09-17 按用户口径收紧）：</p>
 * <ul>
 *   <li><b>没有姓名/岗位/电话/邮箱/公司</b>——那些是个人信息，由本人注册时填写、
 *       在个人中心自己修改（{@code PUT /auth/me}）。管理员改别人的联系方式，
 *       等于「谁是谁」可以由第三方改写。</li>
 *   <li><b>没有 username / password</b>——账号是登录名与审计线索（改掉它，历史就换了个人），
 *       口令只归本人（改口令走 {@code PUT /auth/password}）。</li>
 * </ul>
 */
@Data
public class UserUpdateRequest {

    @NotBlank(message = "请选择角色")
    private String role;

    private Boolean enabled;
}
