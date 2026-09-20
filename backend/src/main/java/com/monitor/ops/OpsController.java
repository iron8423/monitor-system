package com.monitor.ops;

import com.monitor.common.Result;
import com.monitor.common.sse.SseBroadcaster;
import com.monitor.common.util.Times;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import lombok.RequiredArgsConstructor;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 系统运维接口（仅管理员）：给前端「系统运维」页提供**只读**的运行视图。
 *
 * <p>为什么要有它：业务前端（`/admin`）管的是**数据**（项目/测点/设备/规则/用户），
 * 而"这个服务到底跑得怎么样"原先只能靠 SSH + docker logs + psql 看。运维页把这几件事
 * 收进界面：服务与 JVM、数据库与连接池、Flyway 迁移到哪一版、关键表攒了多少行、
 * 以及几个**会影响行为**的开关（严格契约、批大小、时钟容差、上传目录……）。</p>
 *
 * <p><b>三条边界</b>：</p>
 * <ol>
 *   <li><b>只读</b>：这里不提供任何写操作。备份、清理、重跑迁移这类动作留在命令行，
 *       因为它们的失败代价与误操作风险都远高于"看一眼"；</li>
 *   <li><b>不泄密</b>：ingest 密钥、JWT 密钥、数据库口令一律不返回，只回答
 *       "是不是仍在用开发默认值"这种可以公开判断的问题；</li>
 *   <li><b>仅 ADMIN</b>：类级 {@code @PreAuthorize}，和审计日志同一档。运维信息
 *       （连接池、数据量、配置开关）对普通业务角色没有用途，泄露出去反而是情报。</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v1/ops")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class OpsController {

    /** 开发默认 ingest 密钥（与 application.yml 的默认值一致）。只用来回答"是否仍是默认值"。 */
    private static final String DEV_DEFAULT_INGEST_KEY = "dev-ingest-key";

    /** 行数统计的缓存时长（P2-9）：这一页看的是"规模"，不是实时指标。 */
    private static final long STATS_CACHE_MS = 60_000;
    private static final long STATS_CACHE_SECONDS = STATS_CACHE_MS / 1000;

    /** 走近似计数的表（目前只有亿级的那一张）。 */
    private static final Set<String> APPROXIMATE_TABLES = Set.of("measurement");

    private volatile Map<String, Object> statsCache;
    private volatile long statsCachedAt;

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final Flyway flyway;
    private final SseBroadcaster broadcaster;
    private final Environment environment;
    /** 只读地读保留任务的开关与最近一轮统计（写入路径不在这里，见该任务类注释）。 */
    private final com.monitor.telemetry.service.MeasurementRetentionJob retentionJob;

    @Value("${monitor.ingest.strict-contract:false}")
    private boolean strictContract;

    @Value("${monitor.ingest.max-batch-size:2000}")
    private int maxBatchSize;

    @Value("${monitor.ingest.max-collect-ahead-seconds:300}")
    private int maxCollectAheadSeconds;

    @Value("${monitor.ingest.max-receive-ahead-seconds:300}")
    private int maxReceiveAheadSeconds;

    @Value("${monitor.upload.dir:./data/media}")
    private String uploadDir;

    @Value("${monitor.ingest-key:dev-ingest-key}")
    private String ingestKey;

    @Value("${monitor.jwt.expiration:86400000}")
    private long jwtExpirationMs;

    @Value("${monitor.demo-accounts.enabled:true}")
    private boolean demoAccountsEnabled;

    /** 服务、JVM、数据库与连接池的即时状态。 */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        Runtime runtime = Runtime.getRuntime();
        long heapUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long heapMaxMb = runtime.maxMemory() / (1024 * 1024);
        long uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime();

        Map<String, Object> db = new LinkedHashMap<>();
        db.put("url", maskJdbcUrl(connectionUrl()));
        db.put("product", databaseProduct());
        Map<String, Object> pool = new LinkedHashMap<>();
        HikariPoolMXBean mx = hikariPool();
        if (mx != null) {
            pool.put("active", mx.getActiveConnections());
            pool.put("idle", mx.getIdleConnections());
            pool.put("total", mx.getTotalConnections());
            pool.put("waiting", mx.getThreadsAwaitingConnection());
            pool.put("max", hikariMaxPoolSize());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", environment.getProperty("spring.application.name", "monitor-backend"));
        data.put("status", "UP");
        data.put("serverTime", Times.iso(LocalDateTime.now()));
        data.put("startedAt", startedAt(uptimeSeconds));
        data.put("uptimeSeconds", uptimeSeconds);
        data.put("profile", String.join(",", environment.getActiveProfiles()));
        data.put("javaVersion", System.getProperty("java.version"));
        data.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        data.put("heapUsedMb", heapUsedMb);
        data.put("heapMaxMb", heapMaxMb);
        data.put("availableProcessors", runtime.availableProcessors());
        data.put("sseClients", broadcaster.clientCount());
        data.put("database", db);
        data.put("pool", pool);
        return Result.ok(data);
    }

    /** Flyway 迁移：跑过哪些、当前在哪一版、有没有失败的。 */
    @GetMapping("/migrations")
    public Result<Map<String, Object>> migrations() {
        MigrationInfo current = flyway.info().current();
        List<Map<String, Object>> applied = new ArrayList<>();
        for (MigrationInfo info : flyway.info().applied()) {
            applied.add(migrationRow(info));
        }
        List<Map<String, Object>> pending = new ArrayList<>();
        for (MigrationInfo info : flyway.info().pending()) {
            pending.add(migrationRow(info));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("currentVersion", current == null || current.getVersion() == null
                ? "—" : current.getVersion().toString());
        data.put("currentDescription", current == null ? "—" : current.getDescription());
        data.put("appliedCount", applied.size());
        data.put("pendingCount", pending.size());
        data.put("applied", applied);
        data.put("pending", pending);
        return Result.ok(data);
    }

    /**
     * 关键表的行数 + 测量值的时间跨度（决定"这个库攒了多少东西"）。
     *
     * <p><b>大表走近似计数</b>（复查清单 P2-9）：{@code measurement} 在生产基线
     * （1000 点 × 5 秒）下每天约 3456 万行、一个月十亿级，一次 {@code COUNT(*)} 要扫全表，
     * 而运维页只是"看一眼"。PostgreSQL 的 {@code pg_class.reltuples} 是统计信息里的估算，
     * 代价常数级；H2（本地/验收）没有这张表，自动回退精确计数——**不回退成 0**，
     * 0 会被读成"这张表是空的"，比"数字略偏"严重得多。</p>
     *
     * <p><b>近似必须能被识别</b>：响应里带 {@code approximateTables} 与 {@code note}，
     * 前端在那些数字前写「约」。把估算值当精确值读，会得出"昨天导入 3000 万、今天怎么 2900 万"
     * 这类假结论（autovacuum 的估算本来就会浮动）。</p>
     *
     * <p>整体缓存 {@value #STATS_CACHE_SECONDS} 秒：刷新这一页不该每次都把十几条查询重打一遍。
     * 缓存是"数据规模"，不做实时性承诺——{@code cached} 字段告诉调用方这次是不是命中缓存。</p>
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats(
            @RequestParam(defaultValue = "false") boolean exact) {
        if (exact) {
            // 显式要精确值：绕过缓存与近似（见下），并把结果写回缓存——
            // 于是接下来 60 秒内的读取看到的是刚算出来的真值，而不是"缓存里恰好是旧的估算"。
            Map<String, Object> fresh = collectStats(true);
            statsCache = fresh;
            statsCachedAt = System.currentTimeMillis();
            Map<String, Object> copy = new LinkedHashMap<>(fresh);
            copy.put("cached", false);
            return Result.ok(copy);
        }
        Map<String, Object> cached = statsCache;
        if (cached != null && System.currentTimeMillis() - statsCachedAt < STATS_CACHE_MS) {
            Map<String, Object> copy = new LinkedHashMap<>(cached);
            copy.put("cached", true);
            return Result.ok(copy);
        }
        Map<String, Object> data = collectStats(false);
        statsCache = data;
        statsCachedAt = System.currentTimeMillis();
        Map<String, Object> copy = new LinkedHashMap<>(data);
        copy.put("cached", false);
        return Result.ok(copy);
    }

    private Map<String, Object> collectStats(boolean exact) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("project", count("project"));
        counts.put("scene", count("scene"));
        counts.put("monitor_point", count("monitor_point"));
        counts.put("metric", count("metric"));
        counts.put("device", count("device"));
        counts.put("device_point", count("device_point"));
        counts.put("measurement", countTable("measurement", exact));
        counts.put("alarm", count("alarm"));
        counts.put("alarm_action", count("alarm_action"));
        counts.put("media", count("media"));
        counts.put("audit_log", count("audit_log"));
        counts.put("sys_user", count("sys_user"));

        Map<String, Object> measurement = new LinkedHashMap<>();
        measurement.put("earliestCollectTime", isoOrDash(
                "SELECT MIN(collect_time) FROM measurement"));
        measurement.put("latestCollectTime", isoOrDash(
                "SELECT MAX(collect_time) FROM measurement"));
        measurement.put("realtimeRows", countWhere("measurement", "ingest_mode = 'REALTIME'"));
        measurement.put("backfillRows", countWhere("measurement", "ingest_mode = 'BACKFILL'"));
        measurement.put("openAlarms", countWhere("alarm",
                "status NOT IN ('RESOLVED', 'FALSE_ALARM')"));
        measurement.put("deviceAlarms", countWhere("alarm", "alarm_type = 'DEVICE'"));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("counts", counts);
        data.put("measurement", measurement);
        data.put("approximateTables", APPROXIMATE_TABLES.stream().sorted().toList());
        data.put("exact", exact);
        data.put("note", exact
                ? "本次为精确计数（COUNT(*)）；measurement 走 COUNT(*) 在亿级表上会慢，别频繁点"
                : "measurement 行数来自数据库统计信息（近似，标「约」），其余为精确计数；"
                    + "整体缓存 " + STATS_CACHE_SECONDS + " 秒；需要真值时用 ?exact=true");
        return data;
    }

    /** 会影响行为的运行时开关（脱敏：只回答"是不是默认值"，不回显密钥本身）。 */
    @GetMapping("/config")
    public Result<Map<String, Object>> config() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("strictContract", strictContract);
        data.put("maxBatchSize", maxBatchSize);
        data.put("maxCollectAheadSeconds", maxCollectAheadSeconds);
        data.put("maxReceiveAheadSeconds", maxReceiveAheadSeconds);
        data.put("uploadDir", uploadDir);
        data.put("demoAccountsEnabled", demoAccountsEnabled);
        data.put("jwtExpirationHours", jwtExpirationMs / 1000 / 60 / 60);
        // 时区口径（2026-09-18 由 CI 抓出的真 bug）：平台时间一律按 Times.ZONE 生成，
        // 与宿主机的 TZ 无关。放在这里是为了让"这台机器上的时间基准是什么"**看得见**——
        // 裸机部署漏配 TZ 时，这一个字段就能解释"为什么接入整批被判未来"。
        data.put("timeZone", java.util.TimeZone.getDefault().getID());
        data.put("ingestKeyLength", ingestKey == null ? 0 : ingestKey.length());
        data.put("ingestKeyIsDevDefault", DEV_DEFAULT_INGEST_KEY.equals(ingestKey));
        data.put("securityHint", DEV_DEFAULT_INGEST_KEY.equals(ingestKey)
                ? "接入密钥仍是开发默认值：生产部署必须用 MONITOR_INGEST_KEY 覆盖"
                : "接入密钥已按环境变量覆盖");
        // 保留任务的开关也在这里：它是"会不会删数据"的第一现场，与其它运行时开关同列
        data.put("retention", retentionJob.stats());
        return Result.ok(data);
    }

    /**
     * 测量值保留任务的**只读**视图（P1-1）：开关、参数与最近一轮统计。
     *
     * <p>刻意不提供"立即执行一轮"的按钮：那是删数据，属于配置与命令行的事，
     * 与运维页"只读"的边界一致（见类注释）。要看真结果，开 {@code MONITOR_RETENTION_ENABLED}
     * 并等一轮，或直接用验收套件 {@code 19-retention.sh} 的驱动方式。</p>
     */
    @GetMapping("/retention")
    public Result<Map<String, Object>> retention() {
        return Result.ok(retentionJob.stats());
    }

    // ---------------------------------------------------------------- 内部工具

    private Map<String, Object> migrationRow(MigrationInfo info) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("version", info.getVersion() == null ? "—" : info.getVersion().toString());
        row.put("description", info.getDescription());
        row.put("type", info.getType() == null ? "—" : info.getType().name());
        row.put("script", info.getScript());
        row.put("checksum", info.getChecksum());
        // Flyway 给的是 java.util.Date，按平台统一时区（Asia/Shanghai）转成带偏移的 ISO 字符串
        row.put("installedOn", info.getInstalledOn() == null ? null
                : Times.iso(info.getInstalledOn().toInstant().atZone(Times.ZONE).toLocalDateTime()));
        row.put("executionTimeMs", info.getExecutionTime());
        row.put("state", info.getState() == null ? "—" : info.getState().name());
        return row;
    }

    private long count(String table) {
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return value == null ? 0 : value;
    }

    /**
     * 按表给数：{@link #APPROXIMATE_TABLES} 里的表优先用统计信息估算，
     * 估算不可用时（H2、统计信息缺失）**回退精确计数**。
     */
    private long countTable(String table, boolean exact) {
        if (!exact && APPROXIMATE_TABLES.contains(table)) {
            Long approx = approximateRowCount(table);
            if (approx != null) {
                return approx;
            }
        }
        return count(table);
    }

    /** PostgreSQL：{@code pg_class.reltuples}；{@code -1} 表示从未 ANALYZE，交回精确计数。 */
    private Long approximateRowCount(String table) {
        try {
            Long value = jdbcTemplate.queryForObject(
                    "SELECT reltuples::bigint FROM pg_class WHERE relname = ?", Long.class, table);
            return value == null || value < 0 ? null : value;
        } catch (Exception e) {
            return null;   // H2 没有 pg_class：这不是错误，是"这套库不提供估算"
        }
    }

    private long countWhere(String table, String where) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + where, Long.class);
        return value == null ? 0 : value;
    }

    private String isoOrDash(String sql) {
        LocalDateTime value = jdbcTemplate.queryForObject(sql, LocalDateTime.class);
        return value == null ? "—" : Times.iso(value);
    }

    private String startedAt(long uptimeSeconds) {
        return Times.iso(LocalDateTime.now().minusSeconds(uptimeSeconds));
    }

    private String connectionUrl() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getURL();
        } catch (Exception e) {
            return "不可用：" + e.getClass().getSimpleName();
        }
    }

    private String databaseProduct() {
        try (Connection connection = dataSource.getConnection()) {
            var meta = connection.getMetaData();
            return meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion();
        } catch (Exception e) {
            return "不可用：" + e.getClass().getSimpleName();
        }
    }

    /**
     * 连接串脱敏：H2 的 `jdbc:h2:mem:...` 与 PG 的 `jdbc:postgresql://host:port/db` 本来
     * 就不含口令（口令走独立属性），但"本来"不该靠约定——这里把任何 `password=/pwd=` 参数
     * 一律替换成 `***`，将来换驱动也不会漏。
     */
    private String maskJdbcUrl(String url) {
        if (url == null) {
            return "—";
        }
        return url.replaceAll("(?i)(password|pwd)=[^;&]*", "$1=***");
    }

    private HikariPoolMXBean hikariPool() {
        try {
            return dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        } catch (Exception e) {
            return null;
        }
    }

    private int hikariMaxPoolSize() {
        try {
            return dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
        } catch (Exception e) {
            return -1;
        }
    }
}
