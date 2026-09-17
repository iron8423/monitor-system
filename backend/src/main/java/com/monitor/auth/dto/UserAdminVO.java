package com.monitor.auth.dto;

import com.monitor.auth.entity.SysUser;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户管理视图（管理员看别的用户）。
 *
 * <p>与 {@link UserVO}（当前登录用户的自述）刻意分开：这一份多了 {@code enabled} 与
 * {@code createdAt}（管理界面要显示「启用/停用」与建档时间），但**同样不含**
 * {@code password} 与 {@code tokenVersion}——前者是凭据，后者是内部失效机制，
 * 都不该出现在任何读接口里。</p>
 */
@Data
public class UserAdminVO {

    private Long id;
    private String username;
    private String displayName;
    private String role;
    private String roleLabel;
    private Boolean enabled;
    private Long organizationId;
    private String organizationName;
    private String phone;
    private String email;
    private String jobTitle;
    private LocalDateTime createdAt;

    public static UserAdminVO from(SysUser u, String roleLabel, String organizationName) {
        UserAdminVO vo = new UserAdminVO();
        vo.id = u.getId();
        vo.username = u.getUsername();
        vo.displayName = u.getDisplayName();
        vo.role = u.getRole();
        vo.roleLabel = roleLabel;
        vo.enabled = u.getEnabled();
        vo.organizationId = u.getOrganizationId();
        vo.organizationName = organizationName;
        vo.phone = u.getPhone();
        vo.email = u.getEmail();
        vo.jobTitle = u.getJobTitle();
        vo.createdAt = u.getCreatedAt();
        return vo;
    }
}
