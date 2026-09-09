package com.monitor.audit.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.audit.annotation.AuditAction;
import com.monitor.audit.entity.AuditLog;
import com.monitor.audit.mapper.AuditLogMapper;
import com.monitor.auth.security.SecurityUser;
import com.monitor.common.base.Identifiable;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 审计切面：拦截标注 {@link AuditAction} 的写操作，成功后落库（失败不影响主流程）。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {

    private static final int MAX_DETAIL = 1000;

    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;

    @Around("@annotation(auditAction)")
    public Object around(ProceedingJoinPoint pjp, AuditAction auditAction) throws Throwable {
        Object result = pjp.proceed();
        try {
            record(pjp, auditAction);
        } catch (Exception e) {
            log.warn("审计落库失败: {}", e.getMessage());
        }
        return result;
    }

    private void record(ProceedingJoinPoint pjp, AuditAction auditAction) {
        SecurityUser user = currentUser();
        AuditLog entry = new AuditLog();
        entry.setUserId(user != null ? user.getId() : null);
        entry.setUsername(user != null ? user.getUsername() : "anonymous");
        entry.setAction(auditAction.action());
        entry.setTargetType(resolveTargetType(pjp));
        entry.setTargetId(resolveTargetId(pjp.getArgs()));
        entry.setDetail(buildDetail(pjp.getArgs()));
        entry.setIp(currentIp());
        auditLogMapper.insert(entry);
    }

    private SecurityUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof SecurityUser su) {
            return su;
        }
        return null;
    }

    private String resolveTargetType(ProceedingJoinPoint pjp) {
        for (Object arg : pjp.getArgs()) {
            if (arg instanceof Identifiable) {
                return arg.getClass().getSimpleName();
            }
        }
        String name = pjp.getTarget().getClass().getSimpleName();
        if (name.endsWith("Controller")) {
            name = name.substring(0, name.length() - "Controller".length());
        }
        return name;
    }

    private String resolveTargetId(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof Long id) {
                return String.valueOf(id);
            }
        }
        for (Object arg : args) {
            if (arg instanceof Identifiable e && e.getId() != null) {
                return String.valueOf(e.getId());
            }
        }
        return null;
    }

    private String buildDetail(Object[] args) {
        try {
            String json = objectMapper.writeValueAsString(args);
            return json.length() > MAX_DETAIL ? json.substring(0, MAX_DETAIL) : json;
        } catch (Exception e) {
            return null;
        }
    }

    private String currentIp() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        HttpServletRequest request = attrs.getRequest();
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}
