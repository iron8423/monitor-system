package com.monitor.telemetry.service;

import com.monitor.common.util.Times;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测量值保留任务（复查清单 P1-1 的一半：容量与保留策略）。
 *
 * <p><b>为什么需要它</b>：{@code measurement} 单表按生产基线（1000 点 × 5 秒）每天约 3456 万行、
 * 一个月十亿级。没有保留策略时磁盘只增不减，查询也会随数据量线性变慢——
 * 这是"投产前必须回答"的问题，不是"以后再说"的优化。</p>
 *
 * <h3>四条刻意的取舍</h3>
 * <ol>
 *   <li><b>默认关闭</b>（{@code monitor.retention.enabled=false}）。删数据不可逆，
 *       默认打开意味着任何一次本地启动、任何一套演示库都可能在跑。</li>
 *   <li><b>分批删、每轮有上限</b>：一条 {@code DELETE ... WHERE collect_time < cutoff}
 *       在亿级表上会开长事务、长时间持锁、把 WAL 撑大。这里按主键分批删，
 *       每轮最多删 {@code max-rows-per-sweep} 行，批次之间留间隔。</li>
 *   <li><b>只删测量值</b>：警情、处置记录、影像、审计一个字都不动——
 *       它们描述"发生过什么"，与采样点的保留期本来就不该一样。</li>
 *   <li><b>可观测</b>：每轮记录「删了多少、用了多久、截止时间、是否触到上限」，
 *       运维页能看到——看不到的删除是最危险的那种。</li>
 * </ol>
 *
 * <p><b>它不做什么</b>（写在类里，免得被当成"保留策略已经做完了"）：不做降采样物化表、
 * 不做归档导出、不做分区（分区是 PG 侧的结构性改造，见
 * {@code tools/operations/partition_measurement_pg.sql} 与
 * {@code docs/容量与保留策略_20260920.md}）。</p>
 */
@Slf4j
@Component
public class MeasurementRetentionJob {

    /** 单个批次的行数上限：够大才有吞吐，够小才不会长时间持锁。 */
    private static final int DEFAULT_BATCH_SIZE = 5000;

    /** 每轮最多删多少行：防止"停了三个月再启动"时一次扫掉整张表。 */
    private static final int DEFAULT_MAX_ROWS_PER_SWEEP = 200_000;

    private final JdbcTemplate jdbcTemplate;

    @Value("${monitor.retention.enabled:false}")
    private boolean enabled;

    /** 保留窗口（天）：{@code collect_time} 早于"现在 - N 天"的行会被删除。 */
    @Value("${monitor.retention.raw-days:90}")
    private int rawDays;

    @Value("${monitor.retention.batch-size:" + DEFAULT_BATCH_SIZE + "}")
    private int batchSize;

    @Value("${monitor.retention.max-rows-per-sweep:" + DEFAULT_MAX_ROWS_PER_SWEEP + "}")
    private int maxRowsPerSweep;

    /** 批次之间的停顿（毫秒）：给数据库和其它写入留口气。 */
    @Value("${monitor.retention.batch-pause-ms:200}")
    private long batchPauseMs;

    @Value("${monitor.retention.sweep-ms:3600000}")
    private long sweepMs;

    /** 最近一轮的执行结果；首轮之前为 {@code null}。 */
    private final AtomicReference<Map<String, Object>> lastRun = new AtomicReference<>();

    public MeasurementRetentionJob(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 扫描间隔默认 1 小时（首次延迟 1 分钟）。判据（保留多少天、每批多大）都是配置项，
     * 与扫描频率分开——同 {@code DeviceAlarmMonitor} 的分工。
     */
    @Scheduled(fixedDelayString = "${monitor.retention.sweep-ms:3600000}",
            initialDelayString = "${monitor.retention.initial-delay-ms:60000}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        try {
            runOnce();
        } catch (Exception e) {
            // 清理失败绝不能影响主流程：接入、告警、SSE 都不该被它拖下水
            log.error("保留任务执行失败（下一轮继续）", e);
        }
    }

    /**
     * 执行一轮清理并记录统计。
     *
     * <p>public 是为了让**单测**能不靠定时器驱动它（分批循环的边界：删干净、多批、触到单轮上限），
     * HTTP 侧的验收套件则把 {@code sweep-ms} 调到 2 秒来观察真实删除——两条路各测各的：
     * 前者测循环逻辑，后者测"开关 + 真删 + 不动别的表"。</p>
     */
    public Map<String, Object> runOnce() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(Math.max(0, rawDays));
        long startedAt = System.currentTimeMillis();
        long deleted = 0;
        int batches = 0;
        int batch = Math.max(1, batchSize);

        while (deleted < maxRowsPerSweep) {
            int want = (int) Math.min(batch, maxRowsPerSweep - deleted);
            int rows = deleteBatch(cutoff, want);
            if (rows <= 0) {
                break;
            }
            deleted += rows;
            batches += 1;
            if (rows < want) {
                break;   // 这一批没取满 = 已经删干净了
            }
            if (batchPauseMs > 0) {
                try {
                    Thread.sleep(batchPauseMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("lastRunAt", Times.iso(LocalDateTime.now()));
        stats.put("cutoff", Times.iso(cutoff));
        stats.put("deleted", deleted);
        stats.put("batches", batches);
        stats.put("hitRowLimit", deleted >= maxRowsPerSweep);
        stats.put("durationMs", System.currentTimeMillis() - startedAt);
        lastRun.set(stats);

        if (deleted > 0) {
            log.info("保留任务：删除 {} 行测量值（截止 {}，{} 批，{} ms{}}）",
                    deleted, cutoff, batches, stats.get("durationMs"),
                    deleted >= maxRowsPerSweep ? "，已达单轮上限" : "");
        }
        return stats;
    }

    /**
     * 删一批。子查询 + {@code LIMIT} 在 H2 与 PostgreSQL 上都可用；
     * 不用 {@code DELETE ... LIMIT}（那是 MySQL 方言，PG 不支持）。
     */
    private int deleteBatch(LocalDateTime cutoff, int limit) {
        return jdbcTemplate.update(
                "DELETE FROM measurement WHERE id IN ("
                        + "SELECT id FROM measurement WHERE collect_time IS NOT NULL "
                        + "AND collect_time < ? ORDER BY id LIMIT ?)",
                cutoff, limit);
    }

    /** 运维页读的只读视图：开关、参数与最近一轮结果。 */
    public Map<String, Object> stats() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", enabled);
        data.put("rawDays", rawDays);
        data.put("batchSize", batchSize);
        data.put("maxRowsPerSweep", maxRowsPerSweep);
        data.put("sweepMs", sweepMs);
        data.put("lastRun", lastRun.get());
        data.put("note", enabled
                ? "只删测量值（collect_time 早于保留窗口的行）：警情 / 影像 / 审计不动"
                : "当前关闭：不会删除任何数据（默认关闭；开启需设 MONITOR_RETENTION_ENABLED=true）");
        return data;
    }
}
