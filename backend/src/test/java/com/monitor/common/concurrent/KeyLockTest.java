package com.monitor.common.concurrent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KeyLock} 的行为契约。
 *
 * <p>重点不是「能互斥」这件显然的事，而是三条容易被改坏的边界：</p>
 * <ol>
 *   <li><b>不同键不该互相排队</b>——分段锁把不同键映射到同一段是允许的（只是排队），
 *       但「不同键总是串行」会让整个优化变成全局锁，接入吞吐直接退化成单线程。
 *       这里用两个几乎肯定落在不同段的键来钉住它。</li>
 *   <li><b>超时必须照常执行</b>——拿不到锁就放弃执行，会变成「漏报」：
 *       一条已经越限的测值完全不被评估。宁可无锁执行让唯一索引兜底。</li>
 *   <li><b>中断要往上抛且不执行</b>——被中断还去写库，等于把「停止」的意图丢了。</li>
 * </ol>
 */
class KeyLockTest {

    private final KeyLock keyLock = new KeyLock();

    @Test
    void sameKeyIsSerialized() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> order = new CopyOnWriteArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> keyLock.runLocked("P:1:defo_mm", 5000, () -> {
                order.add("first-in");
                inside.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                order.add("first-out");
            }));
            assertTrue(inside.await(5, TimeUnit.SECONDS));

            Future<?> second = pool.submit(() -> keyLock.runLocked("P:1:defo_mm", 5000,
                    () -> order.add("second")));
            // 第二个线程此时必须还在等锁：给它一点时间去「不该成功」
            Thread.sleep(200);
            assertFalse(second.isDone(), "同一键的第二个调用者必须等锁，而不是并行进入");

            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(List.of("first-in", "first-out", "second"), order,
                "同一键必须严格串行：第二个只能在第一个完全退出后进入");
    }

    /** 不同键不应互相阻塞——否则分段锁退化成全局锁。 */
    @Test
    void differentKeysDoNotBlockEachOther() throws Exception {
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean secondRan = new AtomicBoolean(false);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> keyLock.runLocked("P:1:defo_mm", 5000, () -> {
                firstInside.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(firstInside.await(5, TimeUnit.SECONDS));

            Future<?> other = pool.submit(() -> keyLock.runLocked("P:2:rate_mm_d", 5000,
                    () -> secondRan.set(true)));
            other.get(3, TimeUnit.SECONDS);
            assertTrue(secondRan.get(), "不同键之间不应互相等待");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /** 持锁者不放，后到者超时后**照常执行**（返回 false 表示没拿到锁）。 */
    @Test
    void timeoutStillRunsTheAction() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean ranUnlocked = new AtomicBoolean(false);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> keyLock.runLocked("P:3:defo_mm", 5000, () -> {
                inside.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(inside.await(5, TimeUnit.SECONDS));

            boolean locked = keyLock.runLocked("P:3:defo_mm", 100, () -> ranUnlocked.set(true));
            assertFalse(locked, "应该等锁超时");
            assertTrue(ranUnlocked.get(),
                    "超时后仍必须执行：宁可无锁写（唯一索引兜底），也不能让这条测值完全不被评估");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void interruptThrowsAndSkipsTheAction() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> keyLock.runLocked("P:4:defo_mm", 5000, () -> {
                inside.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(inside.await(5, TimeUnit.SECONDS));

            Thread victim = new Thread(() ->
                    keyLock.runLocked("P:4:defo_mm", 5000, ran::incrementAndGet));
            victim.setUncaughtExceptionHandler((t, e) -> { /* 断言在主线程做，这里吞掉 */ });
            victim.start();
            Thread.sleep(200);
            victim.interrupt();
            victim.join(5000);

            assertFalse(victim.isAlive(), "被中断后应尽快退出等待，而不是继续等满超时");
            assertEquals(0, ran.get(), "被中断时不得执行 action——中断意味着「停止」，不是「继续写库」");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /** null 键不 NPE（评估入口的 pointId/metricCode 已在更外层挡掉，但这一层不该是新的崩溃点）。 */
    @Test
    void nullKeyIsTolerated() {
        AtomicBoolean ran = new AtomicBoolean(false);
        assertTrue(keyLock.runLocked(null, 100, () -> ran.set(true)));
        assertTrue(ran.get());
    }

    /** 同线程重入同一键不会自锁——{@link java.util.concurrent.locks.ReentrantLock} 可重入。 */
    @Test
    void reentrantOnSameThreadDoesNotDeadlock() {
        AtomicBoolean inner = new AtomicBoolean(false);
        assertTrue(keyLock.runLocked("P:5:defo_mm", 100,
                () -> keyLock.runLocked("P:5:defo_mm", 100, () -> inner.set(true))));
        assertTrue(inner.get());
    }

    /** action 抛异常时锁必须释放，否则那一段键位永久卡死。 */
    @Test
    void lockIsReleasedWhenActionThrows() {
        assertThrows(IllegalStateException.class, () ->
                keyLock.runLocked("P:6:defo_mm", 100, () -> {
                    throw new IllegalStateException("boom");
                }));
        AtomicBoolean ran = new AtomicBoolean(false);
        assertTrue(keyLock.runLocked("P:6:defo_mm", 100, () -> ran.set(true)),
                "抛异常后锁必须已释放，否则下一次调用会等锁超时");
        assertTrue(ran.get());
    }
}
