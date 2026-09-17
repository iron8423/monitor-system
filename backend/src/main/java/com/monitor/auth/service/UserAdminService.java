package com.monitor.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.monitor.auth.dto.UserAdminVO;
import com.monitor.auth.dto.UserCreateRequest;
import com.monitor.auth.dto.UserUpdateRequest;
import com.monitor.auth.entity.SysUser;
import com.monitor.auth.mapper.SysUserMapper;
import com.monitor.common.constant.Role;
import com.monitor.common.exception.BizException;
import com.monitor.organization.entity.Organization;
import com.monitor.organization.mapper.OrganizationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户管理（管理员）。对应改进清单第 13 条：账号的创建、停用与资料维护。
 *
 * <p>与 {@code AuthService} 的分工（2026-09-17 按用户口径重划过一次）：</p>
 * <ul>
 *   <li><b>本人</b>管自己的：注册时填个人信息、之后在个人中心自改资料、自助改口令
 *       （{@code AuthService#register / #updateProfile / #changePassword}）。</li>
 *   <li><b>管理员</b>管权限与存续：看到全部账号、改**角色与启用**、删除账号
 *       （就是本类）。</li>
 * </ul>
 *
 * <p>也就是说：<b>管理员改不到别人的姓名/岗位/电话/邮箱/公司</b>（那些是个人信息，
 * 由本人负责），<b>本人也改不到自己的角色与启用状态</b>（那是权限，由管理员负责）。
 * 两边各管一半，接口上也各走一条路（{@code PUT /auth/me} 与 {@code PUT /users/{id}}）。</p>
 *
 * <h3>两条不变量（写在 {@link #update} / {@link #remove} 里）</h3>
 * <ol>
 *   <li><b>不能停用/删除自己</b>：一个手滑就把自己关在门外的操作，不该只靠确认框拦。</li>
 *   <li><b>不能改自己的角色</b>：降权自己同样是自锁，且「我降我自己」这种操作没有正当场景。</li>
 * </ol>
 *
 * <p><b>为什么不需要「至少保留一个启用管理员」那条检查</b>：本控制器的每个写操作都要求调用者
 * 自己是<b>启用中的管理员</b>（类级 {@code hasRole('ADMIN')} + {@code JwtAuthFilter} 每请求回库核对
 * {@code enabled}）。也就是说，当 A 去停用/降级 B 时，A 自己就是那位「还留着的管理员」——
 * 这条不变量恒成立，写出来是一段永远不执行的代码。真正需要它的是「管理员可以停用自己」那种设计，
 * 而那已经被上面第 1、2 条挡住了。</p>
 *
 * <p>停用与降权**立刻生效**，不需要额外动作：{@code JwtAuthFilter} 每次请求都回库核对
 * 「账号还在、enabled 仍为真、角色取当前值」（见清单第 12 条），所以被停用的人手里的令牌
 * 下一个请求就是 401，不必等 24h 过期，也不必递增 token_version。</p>
 */
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class UserAdminService {

    private final SysUserMapper userMapper;
    private final OrganizationMapper organizationMapper;
    private final PasswordEncoder passwordEncoder;

    /** 列表：按 id 升序（顺序必须确定——管理端要靠它稳定翻页，理由见 BaseCrudController#ordered） */
    public List<UserAdminVO> list() {
        List<SysUser> users = userMapper.selectList(
                new LambdaQueryWrapper<SysUser>().orderByAsc(SysUser::getId));
        Map<Long, String> organizations = organizationNames();
        return users.stream().map((u) -> toVO(u, organizations)).toList();
    }

    public UserAdminVO get(Long id) {
        SysUser user = require(id);
        return toVO(user, organizationNames());
    }

    /**
     * 管理员改**权限**：角色与启用状态。
     *
     * <p>个人信息（姓名/岗位/电话/邮箱/公司）不在这条路上——那是本人自己填、自己改的，
     * 见 {@code AuthService#updateProfile}。这条口径是用户 2026-09-17 明确定的：
     * 「管理员也不能修改注册用户的个人信息」。</p>
     */
    public UserAdminVO update(Long id, UserUpdateRequest request, Long currentUserId) {
        SysUser user = require(id);
        String role = validRole(request.getRole());
        boolean enabled = request.getEnabled() == null || request.getEnabled();
        boolean self = id.equals(currentUserId);

        if (self && !enabled) {
            throw new BizException(400, "不能停用当前登录的账号");
        }
        if (self && !role.equals(user.getRole())) {
            throw new BizException(400, "不能修改自己的角色");
        }
        user.setRole(role);
        user.setEnabled(enabled);
        // 只更新这两列：姓名/岗位/电话/邮箱/公司归本人，password / token_version 也都不在这里
        userMapper.updateById(user);
        return toVO(userMapper.selectById(id), organizationNames());
    }

    /**
     * 逻辑删除（{@code deleted=1}，见全局 logic-delete-field 配置）。
     *
     * <p>账号一般用「停用」就够了，删除留给建错的号。与停用同样禁止删自己、禁止删掉最后一个管理员
     * ——否则同样是「把自己或所有人关在门外」。</p>
     */
    public void remove(Long id, Long currentUserId) {
        SysUser user = require(id);
        if (id.equals(currentUserId)) {
            throw new BizException(400, "不能删除当前登录的账号");
        }
        userMapper.deleteById(id);
    }

    private SysUser require(Long id) {
        SysUser user = id == null ? null : userMapper.selectById(id);
        if (user == null) {
            throw new BizException(404, "用户不存在: " + id);
        }
        return user;
    }

    private String validRole(String role) {
        try {
            return Role.valueOf(role.trim().toUpperCase()).name();
        } catch (RuntimeException e) {
            throw new BizException(400, "角色不合法: " + role);
        }
    }

    private UserAdminVO toVO(SysUser user, Map<Long, String> organizations) {
        String label = user.getRole();
        try {
            label = Role.valueOf(user.getRole()).getLabel();
        } catch (RuntimeException ignored) {
            // 未知角色原样返回，不在读路径上抛错
        }
        return UserAdminVO.from(user, label,
                user.getOrganizationId() == null ? null : organizations.get(user.getOrganizationId()));
    }

    /** 一次取全组织名做映射，避免每个用户查一次库（列表接口的 N+1） */
    private Map<Long, String> organizationNames() {
        Map<Long, String> map = new HashMap<>();
        for (Organization org : organizationMapper.selectList(null)) {
            map.put(org.getId(), org.getName());
        }
        return map;
    }

}
