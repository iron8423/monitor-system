package com.monitor.auth;

import com.monitor.auth.dto.LoginRequest;
import com.monitor.auth.dto.LoginResponse;
import com.monitor.auth.dto.UserVO;
import com.monitor.auth.security.SecurityUser;
import com.monitor.common.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    @PostMapping("/logout")
    public Result<Void> logout() {
        // 无状态 JWT：服务端无会话，客户端丢弃 token 即可
        return Result.ok();
    }

    @GetMapping("/me")
    public Result<UserVO> me(@AuthenticationPrincipal SecurityUser currentUser) {
        return Result.ok(authService.me(currentUser));
    }
}
