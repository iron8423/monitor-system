package com.monitor.audit.spi;

/**
 * 「按主键取一行当前值」的能力，供 {@code AuditAspect} 在写操作前后各读一次，
 * 把**变更前 / 变更后**落到 {@code audit_log}（复查清单 P1-8）。
 *
 * <p>为什么做成接口而不是让切面反射调用控制器的 {@code mapper()}：反射拿到的
 * {@code Object} 无法在编译期保证"这是一个能按 id 查的 mapper"，写错了只会在
 * 运行时抛 {@code NoSuchMethodException}，而审计自己是不该互相打扰的旁路；
 * 显式实现一个方法，漏了就是编译不过。</p>
 *
 * <p><b>只有主键是 Long 的端点能自动拿到前后值</b>。像
 * {@code PUT /devices/{id}/points/{pointId}/calibration} 那样以复合键定位的端点，
 * 这里给不出"原来那一行"——切面会退化成只记操作后的值（返回值里的实体）。
 * 这条限制是诚实的：与其猜一个 id 去查，不如让 before 空着，
 * 需要精确留痕的端点再单独实现（今天的清单里没有这类需求）。</p>
 */
public interface AuditSnapshotSource {

    /**
     * @param id 主键；{@code null} 返回 {@code null}
     * @return 该行当前值；不存在（或已被逻辑删除）返回 {@code null}
     */
    Object auditSnapshot(Long id);
}
