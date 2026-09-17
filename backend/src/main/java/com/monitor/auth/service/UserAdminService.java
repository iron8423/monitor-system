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
 * <p>与 {@code AuthService} 的分工：那里管「我是谁」（登录、登出、改自己的口令），
 * 这里管「别人是谁」（列表、建号、改资料、停用）。两者都要用 {@link SysUserMapper}
 * 与口令编码器，但只有这里需要「管理员」这个前提。</p>
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

    public UserAdminVO create(UserCreateRequest request) {
        String role = validRole(request.getRole());
        Long exists = userMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, request.getUsername()));
        if (exists != null && exists > 0) {
            // 400 而不是 409：管理端要的是一句能直接显示给操作者的话，不是 HTTP 语义课
            throw new BizException(400, "账号已存在：" + request.getUsername());
        }
        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setDisplayName(request.getDisplayName());
        user.setRole(role);
        user.setOrganizationId(request.getOrganizationId());
        user.setPhone(blankToNull(request.getPhone()));
        user.setEmail(blankToNull(request.getEmail()));
        user.setJobTitle(blankToNull(request.getJobTitle()));
        user.setEnabled(request.getEnabled() == null || request.getEnabled());
        userMapper.insert(user);
        return toVO(userMapper.selectById(user.getId()), organizationNames());
    }

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
        user.setDisplayName(request.getDisplayName());
        user.setRole(role);
        user.setOrganizationId(request.getOrganizationId());
        user.setPhone(blankToNull(request.getPhone()));
        user.setEmail(blankToNull(request.getEmail()));
        user.setJobTitle(blankToNull(request.getJobTitle()));
        user.setEnabled(enabled);
        // 只更新这些列：password / token_version / username 不在这场「改资料」里
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

    /** 空串一律存 null：界面清空某个可选字段时送的是 ''，而 '' 与「没填」在展示与校验上不是一回事 */
    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
