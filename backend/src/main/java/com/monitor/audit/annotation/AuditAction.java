package com.monitor.audit.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要审计的写操作，由 {@code AuditAspect} 切面落库。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditAction {

    /** 操作名称，如「创建」「更新」「删除」 */
    String action();

    /**
     * 为 {@code true} 时，{@code targetId} 取方法里**第一个 String 参数**。
     *
     * <p>默认的取值顺序是「先 Long、再 {@code Identifiable}」，两者都没有就留空——
     * 这对绝大多数端点够用（主键是 Long，或整个实体作为请求体传进来）。但
     * {@code DELETE /media/{mediaId}} 的路径变量是**不透明编码**（{@code "M1024"}），
     * 两种都命中不了，于是审计行会缺 {@code target_id}。</p>
     *
     * <p>刻意做成**显式开关**而不是「扫到 String 就用」：后者会让将来任何一个带
     * String 查询参数的审计端点（比如 {@code ?keyword=xxx}）把关键字写进 target_id，
     * 而且不报错、只在翻审计时才发现。要哪个参数当 targetId，由端点在注解上自己说清楚。</p>
     */
    boolean targetIdFromStringArg() default false;
}
