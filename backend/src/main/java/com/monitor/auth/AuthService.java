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
        String token = jwtUtil.generateToken(su.getId(), su.getUsername(), su.getRole());
        return new LoginResponse(token, toVO(su.getUser()));
    }

    public UserVO me(SecurityUser currentUser) {
        SysUser user = userMapper.selectById(currentUser.getId());
        if (user == null) {
            throw new BizException(404, "用户不存在");
        }
        return toVO(user);
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
