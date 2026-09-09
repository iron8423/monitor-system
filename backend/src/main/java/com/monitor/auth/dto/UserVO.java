package com.monitor.auth.dto;

import com.monitor.auth.entity.SysUser;
import lombok.Data;

/**
 * 用户信息视图（不包含密码）。
 */
@Data
public class UserVO {

    private Long id;
    private String username;
    private String displayName;
    private String role;
    private String roleLabel;
    private Long organizationId;

    public static UserVO from(SysUser u, String roleLabel) {
        UserVO vo = new UserVO();
        vo.id = u.getId();
        vo.username = u.getUsername();
        vo.displayName = u.getDisplayName();
        vo.role = u.getRole();
        vo.roleLabel = roleLabel;
        vo.organizationId = u.getOrganizationId();
        return vo;
    }
}
