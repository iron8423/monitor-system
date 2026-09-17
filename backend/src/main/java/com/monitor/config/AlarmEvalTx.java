package com.monitor.config;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 告警评估的独立短事务（{@code REQUIRES_NEW}）。
 *
 * <p>存在的唯一理由：<b>让应用层锁跨过提交点</b>。评估由接入流程在它自己的事务里调用，
 * 若评估沿用调用方的事务，{@code KeyLock} 在评估方法返回时就释放了，而警情还没提交——
 * 下一个线程拿到锁后用 READ COMMITTED 读不到它，照样再开一条，锁白加。
 * 开一个独立事务，锁的释放就真正落在 COMMIT 之后。</p>
 *
 * <p>代价（已确认接受）：告警的写入不再与测量值同批原子提交。
 * 最坏情况是「测值落库了但警情因为提交失败没了」或反之，两者都是有界的、可重放的不一致；
 * 换来的是「同一测点同一测项至多一条未解除警情」这条不变式真正成立。
 * 注意接入事务回滚时，已提交的警情<b>不会</b>跟着回滚——所以本类只用在评估这条路径上，
 * 且评估本就发生在测值写入之后、不会因为后续失败而需要撤销。</p>
 *
 * <h3>为什么是包装类，而不是直接声明一个 {@code TransactionTemplate} Bean</h3>
 * <p>Spring Boot 自动配置的 {@code TransactionTemplate} 带
 * {@code @ConditionalOnMissingBean(TransactionOperations.class)}。一旦本仓声明了自己的
 * {@code TransactionTemplate} Bean，Boot 就会<b>退让</b>，于是
 * {@code DeviceAlarmMonitor} 与 {@code DataQualityMonitor} 构造器里那两个
 * {@code TransactionTemplate} 注入点要么注入到本类这个 {@code REQUIRES_NEW} 的实例
 * （改变它们既有语义），要么在存在多个候选时直接启动失败。用一个<b>不同类型</b>的
 * Bean 包一层，Boot 的自动配置不受影响，两个监视器拿到的仍是默认的 {@code REQUIRED} 模板。</p>
 */
@Component
@SuppressWarnings("null")
public class AlarmEvalTx {

    private final TransactionTemplate template;

    public AlarmEvalTx(PlatformTransactionManager txManager) {
        this.template = new TransactionTemplate(txManager);
        this.template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // 事务名会出现在日志与连接池的活跃事务里，便于「这个连接为什么被占着」的排查
        this.template.setName("alarmEvalTx");
    }

    /**
     * 在新事务里执行 {@code action}；异常照常向外抛（由 {@code AlarmEngine.evaluate} 记日志吞掉）。
     *
     * <p><b>连接开销</b>：外层事务挂起、内层另取一条连接，同一时刻占两条。
     * 调用方必须先做一次「这次到底会不会写」的廉价预判再进来，
     * 否则高频接入会把 Hikari 池（默认 10）打满。见 {@code AlarmEngine.mightWrite}。</p>
     */
    public void run(Runnable action) {
        template.executeWithoutResult(status -> action.run());
    }
}
