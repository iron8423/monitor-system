package com.monitor.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.monitor.auth.dto.LoginRequest;
import com.monitor.auth.dto.LoginResponse;
import com.monitor.auth.dto.ChangePasswordRequest;
import com.monitor.auth.dto.ProfileUpdateRequest;
import com.monitor.auth.dto.RegisterRequest;
import com.monitor.auth.dto.UserVO;
import com.monitor.auth.entity.SysUser;
import com.monitor.auth.mapper.SysUserMapper;
import com.monitor.auth.security.JwtUtil;
import com.monitor.auth.security.SecurityUser;
import com.monitor.common.constant.Role;
import com.monitor.common.exception.BizException;
import com.monitor.organization.entity.Organization;
import com.monitor.organization.mapper.OrganizationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 认证服务：登录签发 JWT、查询当前用户。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final SysUserMapper userMapper;
    private final OrganizationMapper organizationMapper;
    private final PasswordEncoder passwordEncoder;

    public LoginResponse login(LoginRequest request) {
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));
        SecurityUser su = (SecurityUser) auth.getPrincipal();
        SysUser user = su.getUser();
        String token = jwtUtil.generateToken(su.getId(), su.getUsername(), su.getRole(),
                tokenVersionOf(user));
        return new LoginResponse(token, toVO(user));
    }

    /**
     * 登出：作废该用户此前签发的<b>全部</b>令牌（令牌版本 +1）。
     *
     * <p><b>为什么是「全部」而不是「这一个」</b>：无状态 JWT 里没有 {@code jti} 这类单令牌标识，
     * 服务端拿到的只是一个字符串，无法指认「就是这一张」。要精确到单令牌就得引入黑名单或
     * 每令牌一行记录——那是另一套设计，不是这条能顺带做掉的。所以这里给出的是
     * 「登出即全端下线」，语义诚实：宁可多失效几处，也不要一个看起来能单独登出、
     * 实际什么都没做的接口（那正是此前那个空实现的毛病）。</p>
     *
     * <p>改密同理：任何「口令变了」的路径都应当调 {@link SysUserMapper#bumpTokenVersion}，
     * 否则旧口令签发的令牌仍然可用。</p>
     */
    public void logout(SecurityUser currentUser) {
        if (currentUser == null || currentUser.getId() == null) {
            return;
        }
        userMapper.bumpTokenVersion(currentUser.getId());
    }

    public UserVO me(SecurityUser currentUser) {
        SysUser user = userMapper.selectById(currentUser.getId());
        if (user == null) {
            throw new BizException(404, "用户不存在");
        }
        return toVO(user);
    }

    /**
     * 修改本人密码（个人中心）。
     *
     * <p><b>为什么要先验原口令</b>：改密接口本身就是「用当前会话换取一个新口令」，
     * 只验会话不验口令的话，任何拿到令牌的人（比如借用了别人没锁屏的浏览器）都能改掉口令、
     * 把真正的主人锁在门外。原口令是这里唯一能证明「操作者确实是本人」的东西。</p>
     *
     * <p><b>为什么返回新令牌而不是让人重新登录</b>：改密必须递增 {@code token_version}
     * （否则旧口令签发的令牌仍然可用，见 {@link #logout} 的 javadoc），而那会作废包括
     * 当前这一张在内的全部令牌。若直接返回空，用户会立刻被自己踢下线——同一件事
     * 换个说法是「密码改了、当前页面还得重登」。所以这里递增完版本，用<b>新版本</b>
     * 重签一张给当前会话，其余端（含改密前签发的所有令牌）立即失效。</p>
     */
    public LoginResponse changePassword(SecurityUser currentUser, ChangePasswordRequest request) {
        if (currentUser == null || currentUser.getId() == null) {
            throw new BizException(401, "未登录");
        }
        SysUser user = userMapper.selectById(currentUser.getId());
        if (user == null) {
            throw new BizException(404, "用户不存在");
        }
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            throw new BizException(400, "原密码不正确");
        }
        if (request.getNewPassword().equals(request.getOldPassword())) {
            throw new BizException(400, "新密码不能与原密码相同");
        }

        userMapper.updatePassword(user.getId(), passwordEncoder.encode(request.getNewPassword()));
        // 与登出走同一条机制：递增即「此前签发的全部令牌作废」
        userMapper.bumpTokenVersion(user.getId());

        // 必须重新查一次：上面的 UPDATE 是 SQL 直改，内存里这一行还是旧值（含 token_version）
        SysUser fresh = userMapper.selectById(user.getId());
        if (fresh == null) {
            throw new BizException(404, "用户不存在");
        }
        String token = jwtUtil.generateToken(fresh.getId(), fresh.getUsername(), fresh.getRole(),
                tokenVersionOf(fresh));
        return new LoginResponse(token, toVO(fresh));
    }

    /** 令牌版本缺省按 0：V13 之前建的行在迁移时被填成 0，理论上不会为 null。 */
    private static int tokenVersionOf(SysUser user) {
        return user.getTokenVersion() == null ? 0 : user.getTokenVersion();
    }

    private UserVO toVO(SysUser user) {
        String label = user.getRole();
        try {
            label = Role.valueOf(user.getRole()).getLabel();
        } catch (IllegalArgumentException ignored) {
            // 未知角色原样返回
        }
        return UserVO.from(user, label, organizationNameOf(user));
    }

    /** 个人中心的「公司」= 账号所属组织名；没挂组织或组织被删则留空，由前端显示「—」 */
    private String organizationNameOf(SysUser user) {
        if (user.getOrganizationId() == null) {
            return null;
        }
        Organization org = organizationMapper.selectById(user.getOrganizationId());
        return org == null ? null : org.getName();
    }

    /**
     * 自助注册。
     *
     * <p>三条规则：</p>
     * <ol>
     *   <li><b>账号名全局唯一，但「已删除」的可以重新注册</b>：删除是逻辑删除，行还在库里，
     *       而 {@code uk_sys_user_username} 覆盖所有行——不复用墓碑行，本人就没法再用同一个账号名
     *       （见 {@link SysUserMapper#selectAnyByUsername} 的说明）。复用时会 {@code token_version + 1}，
     *       把上一世的令牌一并作废。</li>
     *   <li><b>不能自助注册成管理员</b>：{@code role} 只收 OPERATOR / ANALYST / MAINTAINER。
     *       要管理员权限，得由既有管理员在用户管理里改角色——那是权限配置，不是个人信息。</li>
     *   <li><b>公司名自动落成组织</b>：平台的可见范围按项目成员算，组织只是展示与归属，
     *       所以注册时允许自由填写公司名，没有就建一个（见 {@link #organizationOf}）。</li>
     * </ol>
     */
    public LoginResponse register(RegisterRequest request) {
        String role = registerableRole(request.getRole());
        SysUser existing = userMapper.selectAnyByUsername(request.getUsername());
        if (existing != null && (existing.getDeleted() == null || existing.getDeleted() == 0)) {
            throw new BizException(400, "账号已存在：" + request.getUsername());
        }
        Long organizationId = organizationOf(request.getCompany());

        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setDisplayName(request.getDisplayName());
        user.setRole(role);
        user.setOrganizationId(organizationId);
        user.setPhone(trimToNull(request.getPhone()));
        user.setEmail(trimToNull(request.getEmail()));
        user.setJobTitle(trimToNull(request.getJobTitle()));
        user.setEnabled(true);

        if (existing != null) {
            user.setId(existing.getId());
            userMapper.revive(user);
        } else {
            userMapper.insert(user);
        }
        SysUser created = userMapper.selectById(user.getId());
        String token = jwtUtil.generateToken(created.getId(), created.getUsername(), created.getRole(),
                tokenVersionOf(created));
        return new LoginResponse(token, toVO(created));
    }

    /** 本人改自己的资料（「个人中心」）。改不了角色与启用状态——那不是个人信息。 */
    public UserVO updateProfile(SecurityUser currentUser, ProfileUpdateRequest request) {
        if (currentUser == null || currentUser.getId() == null) {
            throw new BizException(401, "未登录");
        }
        SysUser user = userMapper.selectById(currentUser.getId());
        if (user == null) {
            throw new BizException(404, "用户不存在");
        }
        // 只写这几个列：role / enabled / password / username 都不在这场更新里
        user.setDisplayName(request.getDisplayName());
        user.setOrganizationId(organizationOf(request.getCompany()));
        user.setJobTitle(trimToNull(request.getJobTitle()));
        user.setPhone(trimToNull(request.getPhone()));
        user.setEmail(trimToNull(request.getEmail()));
        userMapper.updateById(user);
        return toVO(userMapper.selectById(user.getId()));
    }

    /**
     * 公司名 → 组织 id：同名复用，没有就建一个。
     *
     * <p>为什么不直接让用户填 organizationId：注册的人不会知道库里那套 id，
     * 也不该为了注册先去建组织。公司名是自由文本，平台负责把它落成一条组织记录。</p>
     */
    private Long organizationOf(String company) {
        String name = trimToNull(company);
        if (name == null) {
            return null;
        }
        Organization existing = organizationMapper.selectOne(
                new LambdaQueryWrapper<Organization>().eq(Organization::getName, name).last("LIMIT 1"));
        if (existing != null) {
            return existing.getId();
        }
        Organization org = new Organization();
        org.setName(name);
        organizationMapper.insert(org);
        return org.getId();
    }

    /** 自助注册可选的角色：ADMIN 不在其中（理由见 {@link #register}） */
    private String registerableRole(String role) {
        String value;
        try {
            value = Role.valueOf(role.trim().toUpperCase()).name();
        } catch (RuntimeException e) {
            throw new BizException(400, "角色不合法: " + role);
        }
        if (Role.ADMIN.name().equals(value)) {
            throw new BizException(400, "注册不能选择管理员角色");
        }
        return value;
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
