package com.monitor.audit.service;

import com.monitor.audit.entity.AuditLog;
import com.monitor.audit.mapper.AuditLogMapper;
import com.monitor.auth.security.SecurityUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Set;

/**
 * 「越权写尝试」留痕（复查清单 P1-8 的第三块）。
 *
 * <p>{@code @AuditAction} 只罩得住"进了方法体"的调用，而权限拒绝发生在方法之前，
 * 于是"有没有人试图改过别人的数据"这个问题的答案会是一片空白——恰恰是最该留痕的一类尝试。</p>
 *
 * <p><b>为什么要单独一个组件、而不是写在某一个处理器里</b>：403 有**两条**产出路径，
 * 实测在 2026-09-18 撞到过：</p>
 * <ol>
 *   <li>方法级 {@code @PreAuthorize} 拒绝 → 异常被 {@code GlobalExceptionHandler} 接住
 *       （控制器 advice 在过滤器之前处理，走的**不是** Spring Security 的处理器）；</li>
 *   <li>过滤器链层面的拒绝（URL 规则）→ {@code RestAccessDeniedHandler}。</li>
 * </ol>
 * <p>只在其中一处写，另一条路径就静默漏记——两条都调这一个组件，语义才不会分叉。</p>
 *
 * <p>三条收敛，避免把审计表刷成噪声：只记写方法（读接口的 403 在数据范围校验里是正常结果）、
 * 只记已认证用户（未认证走 401，没有"是谁"可记）、落库失败只 warn（403 的响应不能被审计拖垮）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeniedWriteAuditor {

    /** 与 HTTP 语义一致的写方法白名单。 */
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    /** 审计里的动作名；前端标签映射没命中时显示为普通标签，不影响可读性。 */
    public static final String ACTION = "越权尝试";

    private final AuditLogMapper auditLogMapper;

    /** 当前请求是"已认证用户发起的写操作"时记一条 FAILED 审计；其它情况什么都不做。 */
    public void recordIfWriteAttempt() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            HttpServletRequest request = attrs == null ? null : attrs.getRequest();
            if (request == null || !WRITE_METHODS.contains(request.getMethod())) {
                return;
            }
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !(auth.getPrincipal() instanceof SecurityUser su)) {
                return;
            }
            String target = request.getMethod() + " " + request.getRequestURI();
            AuditLog entry = new AuditLog();
            entry.setUserId(su.getId());
            entry.setUsername(su.getUsername());
            entry.setAction(ACTION);
            // targetType 固定 HttpRequest、targetId 是请求路径：从 URI 反推"是哪个实体"会猜错，
            // 而审计里猜错比留空更糟——查的人会据此排除掉真正的目标。
            entry.setTargetType("HttpRequest");
            entry.setTargetId(request.getRequestURI());
            entry.setDetail(target);
            entry.setResult("FAILED");
            entry.setErrorMessage("无权限访问：" + target);
            entry.setIp(clientIp(request));
            auditLogMapper.insert(entry);
        } catch (Exception e) {
            log.warn("越权尝试留痕失败: {}", e.getMessage());
        }
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded;
        }
        return request.getRemoteAddr();
    }
}
