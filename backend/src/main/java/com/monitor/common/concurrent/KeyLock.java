package com.monitor.common.concurrent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 按业务键串行化的进程内锁（分段锁 / lock striping）。
 *
 * <p>用途：告警评估是典型的 check-then-act——先查「该测点该测项有没有未解除警情」，
 * 再决定触发 / 升级 / 解除。两个线程同时查到「没有」时就会各开一条警情。
 * 让同一把键上的评估串行执行，第二次进来的线程就能查到第一次刚提交的那条。</p>
 *
 * <h3>为什么必须是「锁跨过提交」</h3>
 * <p>锁本身不解决竞态，<b>锁的持有区间跨过了提交点</b>才解决。假如评估仍在调用方的事务里，
 * 锁在 {@code doEvaluate} 返回时就释放了，而那批写还<b>没提交</b>：
 * 下一个线程拿到锁，用 READ COMMITTED 去读，「刚写的那条」对它不可见，
 * 于是照样再开一条。锁加了，重复警情一条不少。</p>
 * <p>所以 {@code AlarmEngine} 把评估放进 {@link com.monitor.config.AlarmEvalTx} 的独立短事务里，
 * 锁区间的末端才真正落在 COMMIT 之后。两者是<b>一个整体</b>，只改一处等于没改。</p>
 *
 * <h3>为什么用分段锁而不是 {@code ConcurrentHashMap<String, Lock>}</h3>
 * <p>键（{@code P:<pointId>:<metricCode>}）随测点数增长，按字符串缓存锁对象会让锁表无限增长，
 * 还得自己处理回收。固定 64 段的冲突率对「同一测点同一测项的并发评估」这种局部热点完全够用，
 * 且<strong>内存恒定</strong>、无回收问题。代价是不同键可能落在同一段上而互相排队——
 * 只是排队，不影响正确性。</p>
 *
 * <h3>不变量：一次只持有一把</h3>
 * <p>调用方在持锁期间<b>不得</b>再调用 {@link #runLocked}。只要坚持这一条就不会死锁：
 * 线程永远只拿着一把锁去等另一把。（同线程重入同一把不会自锁——{@link ReentrantLock}
 * 可重入；但重入会让「锁跨过提交」的推理失效，因此同样不该出现。）</p>
 *
 * <h3>拿不到锁时怎么办</h3>
 * <p>超时后<b>照常执行</b>（不持锁），只记一条 warn。理由：这是最后一道应用层优化，
 * 真正的兜底是 {@code alarm.open_key} 上的唯一索引（V14）。放弃执行会让一条已经越限的测值
 * <b>完全不被评估</b>——漏报比重复报严重得多；而不持锁执行最坏就是撞上唯一索引、
 * 该短事务回滚、异常被 {@code AlarmEngine.evaluate} 吞掉，而占着锁的那个线程
 * 本来就已经把警情写好了。</p>
 */
@Slf4j
@Component
public class KeyLock {

    /** 分段数：2 的幂，{@code hashCode} 取模退化为位与，且足够覆盖常见并发度。 */
    private static final int STRIPES = 64;

    private final ReentrantLock[] locks = new ReentrantLock[STRIPES];

    public KeyLock() {
        for (int i = 0; i < STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    /**
     * 在 {@code key} 对应的锁保护下执行 {@code action}。
     *
     * @param key           业务键，null 视为空串（不 NPE，也不与别的键混同）
     * @param timeoutMillis 等待上限；超时则<b>不持锁</b>执行并记 warn，见类注释
     * @param action        受保护的动作。**不要**在这里面再调用本方法
     * @return true 表示持锁执行；false 表示超时后无锁执行（调用方一般无需区分，
     *         需要观测时可用返回值打日志）
     */
    public boolean runLocked(String key, long timeoutMillis, Runnable action) {
        ReentrantLock lock = locks[Math.floorMod(key == null ? 0 : key.hashCode(), STRIPES)];
        boolean acquired;
        try {
            acquired = lock.tryLock(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // 中断语义要保住：恢复标志位再抛，让上层（评估入口）记日志后把中断继续传下去。
            // 这里**不执行** action——被中断时还去写库，等于把「停止」的意图丢了。
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待告警评估锁被中断 key=" + key, e);
        }
        if (!acquired) {
            log.warn("等待告警评估锁超时 {}ms，改为无锁执行（唯一索引兜底）key={}", timeoutMillis, key);
        }
        try {
            action.run();
            return acquired;
        } finally {
            if (acquired) {
                lock.unlock();
            }
        }
    }
}
