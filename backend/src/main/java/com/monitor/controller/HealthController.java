package com.monitor.controller;

import com.monitor.common.Result;
import com.monitor.common.util.Times;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运行健康检查（A0 交付）。
 *
 * <p><b>两个端点分工要说清楚</b>（复查清单 P0-6）：</p>
 * <ul>
 *   <li>{@code /health}——<b>liveness</b>：进程还活着、Spring 上下文还没倒，就返回 UP。
 *       它刻意<b>不</b>碰数据库：数据库短暂抖动时重启进程既治不了病，还会把内存里的
 *       调度状态一起丢掉。</li>
 *   <li>{@code /health/ready}——<b>readiness</b>：真的去执行一次 {@code SELECT 1}
 *       并汇报连接池余额。数据库挂掉 / 连接池耗尽时它返回 <b>503</b>，
 *       编排（docker healthcheck）与监控打的是它。</li>
 * </ul>
 *
 * <p>为什么需要第二个端点：改造前 backend healthcheck 打的就是 {@code /health}，
 * 而它无条件返回 UP——数据库不可用时容器照样 (healthy)，编排不重启、监控不告警，
 * 值班员看到的是"服务正常但数据不动"（假绿）。运维页能看出真相，但**编排与监控不该
 * 依赖人去看页面**。</p>
 *
 * <p>503 的响应体仍是统一的 {@code Result} 信封（{@code code = 503}），
 * 这样 curl -f / 编排判定看的是 HTTP 状态码，而人看到的仍是同一套结构。</p>
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    /** liveness：进程活着就算 UP，不查任何外部依赖。 */
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        return Result.ok(Map.of(
                "status", "UP",
                "service", "monitor-backend",
                "time", OffsetDateTime.now().toString()
        ));
    }

    /**
     * readiness：数据库可达 + 连接池还有可用连接才算就绪，否则 503。
     *
     * <p>探针刻意是 {@code SELECT 1}（而不是建表、查业务表）：它验证的是"这条路还通"，
     * 不依赖任何业务数据的状态，也不会在空库（验收 {@code --fresh}）上误报。</p>
     */
    @GetMapping("/health/ready")
    public ResponseEntity<Result<Map<String, Object>>> ready() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", "monitor-backend");
        data.put("time", Times.iso(LocalDateTime.now()));

        String database = "UP";
        try {
            Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            if (one == null || one != 1) {
                database = "DOWN";
            }
        } catch (Exception e) {
            database = "DOWN";
            data.put("databaseError", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        data.put("database", database);

        HikariPoolMXBean pool = hikariPool();
        if (pool != null) {
            Map<String, Object> poolInfo = new LinkedHashMap<>();
            poolInfo.put("active", pool.getActiveConnections());
            poolInfo.put("idle", pool.getIdleConnections());
            poolInfo.put("total", pool.getTotalConnections());
            poolInfo.put("waiting", pool.getThreadsAwaitingConnection());
            data.put("pool", poolInfo);
            // 池子"一个可用连接都没有"同样是不可就绪：此时 SELECT 1 可能刚好抢到最后一个
            // 空闲连接而通过，但后续请求会排队——那正是要提前暴露的状态。
            if ("UP".equals(database) && pool.getTotalConnections() <= 0) {
                database = "DOWN";
                data.put("database", database);
                data.put("databaseError", "连接池没有可用连接");
            }
        }

        boolean up = "UP".equals(database);
        data.put("status", up ? "UP" : "DOWN");
        Result<Map<String, Object>> body = up
                ? Result.ok(data)
                : Result.fail(503, "数据库不可用：" + data.getOrDefault("databaseError", "未知原因"));
        return ResponseEntity.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    /** 连接池指标只在 Hikari 下有意义；换池子/包一层代理时这里静默降级，不影响判定。 */
    private HikariPoolMXBean hikariPool() {
        try {
            return dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        } catch (Exception e) {
            return null;
        }
    }
}
