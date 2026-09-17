package com.monitor.auth.web;

import com.monitor.audit.annotation.AuditAction;
import com.monitor.auth.dto.UserAdminVO;
import com.monitor.auth.dto.UserCreateRequest;
import com.monitor.auth.dto.UserUpdateRequest;
import com.monitor.auth.security.SecurityUser;
import com.monitor.auth.service.UserAdminService;
import com.monitor.common.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户管理（管理员）：清单第 13 条缺的那一半。
 *
 * <p>为什么单独一个控制器而不是塞进 {@code AuthController}：{@code /auth/**} 是
 * 「我自己是谁」的入口（登录、登出、改自己的口令），而这个控制器是「别人是谁」。
 * 两者**权限模型相反**——{@code /auth/logout} 甚至是不鉴权放行的，混在一个类里，
 * 类级 {@code @PreAuthorize} 就没法写。</p>
 *
 * <p>类级 {@code @PreAuthorize("hasRole('ADMIN')")} 与 {@code AuditLogController} 同款：
 * 边界在方法进入之前，而不是靠前端藏按钮。非管理员直达这些接口一律 403。</p>
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final UserAdminService userAdminService;

    @GetMapping
    public Result<List<UserAdminVO>> list() {
        return Result.ok(userAdminService.list());
    }

    @GetMapping("/{id}")
    public Result<UserAdminVO> get(@PathVariable Long id) {
        return Result.ok(userAdminService.get(id));
    }

    @PostMapping
    @AuditAction(action = "新建用户")
    public Result<UserAdminVO> create(@Valid @RequestBody UserCreateRequest request) {
        return Result.ok(userAdminService.create(request));
    }

    /**
     * 改资料 / 改角色 / 停用启用。**不含口令**（口令有自己的入口）。
     *
     * <p>{@code targetIdFromStringArg} 不需要：路径变量就是 Long 主键，审计切面自己取得。
     * 当前登录用户由后端从会话里取（不信任请求体），用来挡住「停用自己 / 改自己的角色」。</p>
     */
    @PutMapping("/{id}")
    @AuditAction(action = "更新用户")
    public Result<UserAdminVO> update(@PathVariable Long id,
                                      @Valid @RequestBody UserUpdateRequest request,
                                      @AuthenticationPrincipal SecurityUser currentUser) {
        return Result.ok(userAdminService.update(id, request, currentUser == null ? null : currentUser.getId()));
    }

    @DeleteMapping("/{id}")
    @AuditAction(action = "删除用户")
    public Result<Void> remove(@PathVariable Long id,
                               @AuthenticationPrincipal SecurityUser currentUser) {
        userAdminService.remove(id, currentUser == null ? null : currentUser.getId());
        return Result.ok();
    }
}
