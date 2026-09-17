package com.monitor.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改本人密码（个人中心）。
 *
 * <p>长度只在<b>新口令</b>上卡：演示账号的初始口令是 6 位（种子数据，见
 * {@code DataInitializer}），若在这里对旧口令也卡 8 位，那批账号反而改不了密码。
 * 旧口令的正确性由服务层用 bcrypt 比对回答——长度规则是「新设口令」的策略，
 * 不是「已有口令」的校验。</p>
 */
@Data
public class ChangePasswordRequest {

    @NotBlank(message = "请输入原密码")
    private String oldPassword;

    @NotBlank(message = "请输入新密码")
    @Size(min = 8, max = 64, message = "新密码长度需为 8–64 位")
    private String newPassword;
}
