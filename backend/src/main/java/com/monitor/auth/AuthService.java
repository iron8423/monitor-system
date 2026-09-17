package com.monitor.auth;

import com.monitor.auth.dto.LoginRequest;
import com.monitor.auth.dto.LoginResponse;
import com.monitor.auth.dto.UserVO;
import com.monitor.auth.entity.SysUser;
import com.monitor.auth.mapper.SysUserMapper;
import com.monitor.auth.security.JwtUtil;
import com.monitor.auth.security.SecurityUser;
import com.monitor.common.constant.Role;
import com.monitor.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
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
        return UserVO.from(user, label);
    }
}
