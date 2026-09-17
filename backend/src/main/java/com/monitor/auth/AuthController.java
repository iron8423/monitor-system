package com.monitor.auth;

import com.monitor.audit.annotation.AuditAction;
import com.monitor.auth.dto.ChangePasswordRequest;
import com.monitor.auth.dto.LoginRequest;
import com.monitor.auth.dto.LoginResponse;
import com.monitor.auth.dto.ProfileUpdateRequest;
import com.monitor.auth.dto.RegisterRequest;
import com.monitor.auth.dto.UserVO;
import com.monitor.auth.security.SecurityUser;
import com.monitor.common.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（A1）：登录 / 登出 / 当前用户。
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.ok(authService.login(request));
    }

    /**
     * 登出：作废该用户此前签发的全部令牌，不只是让客户端丢掉。
     *
     * <p>此前这里是空的（「无状态 JWT，客户端丢弃即可」）——那意味着一个拿到令牌的人，
     * 在令牌到期前无论如何都踢不掉。现在按用户递增令牌版本，登出即全端下线。</p>
     */
    @PostMapping("/logout")
    public Result<Void> logout(@AuthenticationPrincipal SecurityUser currentUser) {
        authService.logout(currentUser);
        return Result.ok();
    }

    @GetMapping("/me")
    public Result<UserVO> me(@AuthenticationPrincipal SecurityUser currentUser) {
        return Result.ok(authService.me(currentUser));
    }

    /**
     * 自助注册（免登录，见 SecurityConfig 的放行名单）。
     *
     * <p>成功后**直接返回令牌**（等价于注册即登录）：本人刚填完账号密码，
     * 再让他去登录页重敲一遍没有信息量。</p>
     */
    @PostMapping("/register")
    public Result<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        return Result.ok(authService.register(request));
    }

    /**
     * 本人修改自己的资料（个人中心）。
     *
     * <p>与 {@code /auth/password} 一样**刻意不放进放行名单**：它是「我的资料」，
     * 必须有有效会话。管理员那条改**权限**的路在 {@code /api/v1/users/{id}}（只改角色与启用），
     * 两条路各管一半，都改不到对方的字段。</p>
     */
    @PutMapping("/me")
    public Result<UserVO> updateProfile(@AuthenticationPrincipal SecurityUser currentUser,
                                        @Valid @RequestBody ProfileUpdateRequest request) {
        return Result.ok(authService.updateProfile(currentUser, request));
    }

    /**
     * 修改本人密码：成功后**返回新令牌**（当前会话继续用，其余端全部失效）。
     *
     * <p>这个端点**刻意不**放进 {@code SecurityConfig} 的放行名单——它必须带有效会话，
     * 否则就成了「谁都能改别人密码」。注意 {@code /auth/logout} 是放行的（它只做作废），
     * 两者放行与否不同不是疏忽。</p>
     *
     * <p>审计只记「谁、什么时候、做了什么」：{@code AuditAspect} 取的是当前登录用户，
     * 请求体里的口令一个字都不落库。</p>
     */
    @PostMapping("/password")
    @AuditAction(action = "修改密码")
    public Result<LoginResponse> changePassword(@AuthenticationPrincipal SecurityUser currentUser,
                                                @Valid @RequestBody ChangePasswordRequest request) {
        return Result.ok(authService.changePassword(currentUser, request));
    }
}
