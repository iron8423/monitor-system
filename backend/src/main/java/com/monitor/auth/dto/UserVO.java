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

    /**
     * 组织名（个人中心里的「公司」）。取不到组织时保持 null，由前端显示「—」——
     * 返回空串会和「组织名本来就是空」混在一起，两种情况的处置不同。
     */
    private String organizationName;

    /** 个人资料（V17）：联系电话 / 邮箱 / 岗位。均可能为空。 */
    private String phone;
    private String email;
    private String jobTitle;

    public static UserVO from(SysUser u, String roleLabel) {
        return from(u, roleLabel, null);
    }

    public static UserVO from(SysUser u, String roleLabel, String organizationName) {
        UserVO vo = new UserVO();
        vo.id = u.getId();
        vo.username = u.getUsername();
        vo.displayName = u.getDisplayName();
        vo.role = u.getRole();
        vo.roleLabel = roleLabel;
        vo.organizationId = u.getOrganizationId();
        vo.organizationName = organizationName;
        vo.phone = u.getPhone();
        vo.email = u.getEmail();
        vo.jobTitle = u.getJobTitle();
        return vo;
    }
}
