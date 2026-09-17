package com.monitor.auth;

import com.monitor.auth.dto.LoginRequest;
import com.monitor.auth.dto.LoginResponse;
import com.monitor.auth.dto.ChangePasswordRequest;
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
}
