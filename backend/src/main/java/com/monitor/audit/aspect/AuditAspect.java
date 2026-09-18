package com.monitor.audit.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.audit.annotation.AuditAction;
import com.monitor.audit.entity.AuditLog;
import com.monitor.audit.mapper.AuditLogMapper;
import com.monitor.audit.spi.AuditSnapshotSource;
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
 * 审计切面：拦截标注 {@link AuditAction} 的写操作，把「谁、何时、对什么、做了什么、
 * <b>原来是什么值、现在是什么值、成功还是失败</b>」落进 {@code audit_log}。
 *
 * <p><b>失败也记</b>（P1-8，V23 起）。旧实现在 {@code pjp.proceed()} 之后才落库，
 * 方法一抛异常就什么都不留——想回答"有没有人试图改过"时是空白。
 * 现在：成功记 {@code SUCCESS}，抛异常记 {@code FAILED} + 原因，然后把异常**原样抛出**
 * （审计是旁路，绝不能改变主流程的成败与返回码）。</p>
 *
 * <p><b>前后值怎么来的</b>：写操作前后各按主键查一次
 * （{@link AuditSnapshotSource}，由 {@code BaseCrudController} 实现）。
 * 为什么要重查而不是用入参：{@code updateById} 跳过 null 字段，入参只描述了"这次要改哪些"，
 * 不是"改完是什么样"。删除时 after 为空、新建时 before 为空——这两端本来就什么都没有。</p>
 *
 * <p>审计自身的任何异常都不许外泄：落库失败只记一行 warn，主流程照常返回。</p>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {

    private static final int MAX_DETAIL = 1000;
    private static final int MAX_SNAPSHOT = 2048;
    private static final int MAX_ERROR = 512;
    private static final String RESULT_SUCCESS = "SUCCESS";
    private static final String RESULT_FAILED = "FAILED";

    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;

    @Around("@annotation(auditAction)")
    public Object around(ProceedingJoinPoint pjp, AuditAction auditAction) throws Throwable {
        // 先把"原来是什么"拍下来：proceed() 之后那一行已经变了，再查就晚了
        Long beforeId = snapshotId(pjp.getArgs());
        Object before = loadSnapshot(pjp, beforeId);

        Object result;
        try {
            result = pjp.proceed();
        } catch (Throwable failure) {
            // 失败也要留痕：这里**不吞**异常，记完原样抛出（否则会改掉接口的返回码）
            record(pjp, auditAction, before, null, RESULT_FAILED, failure.getMessage());
            throw failure;
        }

        // 新建时 id 是 insert 之后才有的（实体字段被回填），所以这里再解析一次
        Long afterId = snapshotId(pjp.getArgs());
        Object after = afterId == null ? unwrapEntity(result) : loadSnapshot(pjp, afterId);
        record(pjp, auditAction, before, after, RESULT_SUCCESS, null);
        return result;
    }

    /**
     * 落一条审计。**任何异常都只记日志**：审计写不进去不该把一次成功的业务操作变成 500，
     * 也不该掩盖真实的业务异常（失败路径上它是在 catch 里被调用的）。
     */
    private void record(ProceedingJoinPoint pjp, AuditAction auditAction, Object before, Object after,
                        String result, String errorMessage) {
        try {
            SecurityUser user = currentUser();
            AuditLog entry = new AuditLog();
            entry.setUserId(user != null ? user.getId() : null);
            entry.setUsername(user != null ? user.getUsername() : "anonymous");
            entry.setAction(auditAction.action());
            entry.setTargetType(resolveTargetType(pjp));
            entry.setTargetId(resolveTargetId(pjp.getArgs(), auditAction));
            entry.setDetail(buildDetail(pjp.getArgs()));
            entry.setBeforeJson(json(before, MAX_SNAPSHOT));
            entry.setAfterJson(json(after, MAX_SNAPSHOT));
            entry.setResult(result);
            entry.setErrorMessage(truncate(errorMessage, MAX_ERROR));
            entry.setIp(currentIp());
            auditLogMapper.insert(entry);
        } catch (Exception e) {
            log.warn("审计落库失败: {}", e.getMessage());
        }
    }

    /**
     * 从参数里解析"这一行的主键"：先 Long（路径变量），再 {@link Identifiable} 的 id。
     *
     * <p>不解析 String（不透明编码那类端点见 {@link AuditAction#targetIdFromStringArg()}）：
     * 拿一个编码去 {@code selectById} 只会得到 null，白白多一次查询。</p>
     */
    private Long snapshotId(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof Long id) {
                return id;
            }
        }
        for (Object arg : args) {
            if (arg instanceof Identifiable e && e.getId() != null) {
                return e.getId();
            }
        }
        return null;
    }

    /** 主键查旧值；控制器没实现 {@link AuditSnapshotSource}（或查不动了）时返回 null。 */
    private Object loadSnapshot(ProceedingJoinPoint pjp, Long id) {
        if (id == null || !(pjp.getTarget() instanceof AuditSnapshotSource source)) {
            return null;
        }
        try {
            return source.auditSnapshot(id);
        } catch (Exception e) {
            log.warn("审计取原值失败 id={}: {}", id, e.getMessage());
            return null;
        }
    }

    /**
     * 没有主键可查时（复合键端点，如雷达标定）退而用返回值里的实体当"操作后的值"。
     * {@code Result} 的统一信封在这里被拆开——只认 data 是 {@link Identifiable} 的情况。
     */
    private Object unwrapEntity(Object result) {
        if (result instanceof com.monitor.common.Result<?> envelope) {
            Object data = envelope.getData();
            return data instanceof Identifiable ? data : null;
        }
        return result instanceof Identifiable ? result : null;
    }

    private String json(Object value, int max) {
        if (value == null) {
            return null;
        }
        try {
            return truncate(objectMapper.writeValueAsString(value), max);
        } catch (Exception e) {
            return null;
        }
    }

    /** 截断时打标记：读的人必须知道"这条被截过"，而不是以为原值就这么短。 */
    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max - 3) + "...";
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

    /**
     * 取值顺序：先 Long（主键），再 {@link Identifiable}（整个实体作为请求体）。
     *
     * <p>路径变量是**不透明编码**而非数字主键的端点（目前只有
     * {@code DELETE /media/{mediaId}}）两种都命中不了，需要用
     * {@link AuditAction#targetIdFromStringArg()} 显式声明——见该属性的注释，
     * 这里不做「扫到 String 就用」的兜底。</p>
     */
    private String resolveTargetId(Object[] args, AuditAction auditAction) {
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
        if (auditAction.targetIdFromStringArg()) {
            for (Object arg : args) {
                if (arg instanceof String s && !s.isBlank()) {
                    return s;
                }
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
