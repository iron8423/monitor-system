package com.monitor.alarm;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 清单第 32 条所依赖的**两条数据库语义**，在 H2 上的行为取证。
 *
 * <p>为什么要有这么一个「不测业务代码、只测数据库」的测试：告警并发一致性最后落在两件事上——
 * ① 未解除唯一索引（V14）会拒绝第二条同键行；② 「带前置条件的 UPDATE」在并发下返回 0 行。
 * 这两条都<b>不是我们代码的行为，是数据库的行为</b>，而 H2（本地 / 验收）与 PostgreSQL（生产）
 * 在这两点上**默认并不一致**。下表是 H2 2.3.232 上的实测（做法与下面两条并发用例相同：
 * T1 占住行锁 3 秒后提交，T2 拿过期条件去写）：</p>
 * <pre>
 *                           H2 默认        H2 + LOCK_TIMEOUT=10000   PostgreSQL
 *  CAS（条件已不成立）      50200 锁超时    返回 0 行                 返回 0 行
 *  并发插入同一 open_key    50200 锁超时    23505 唯一约束冲突        23505 唯一约束冲突
 * </pre>
 * <p>也就是说：不加那个参数，本地<b>根本观察不到</b> CAS 与唯一约束本该有的语义，
 * 看到的全是「锁超时」。而 {@code AlarmService.act} 的 409、
 * {@code AlarmEngine} 升级/解除的跳过写入、V14 的多实例兜底，全都建立在「返回 0 行 / 23505」上——
 * 一条只在生产才成立的路径，等于没测。</p>
 *
 * <p>所以 {@code application.yml} 给 H2 加了 {@code LOCK_TIMEOUT=10000}，
 * 本测试<b>直接读那份配置</b>并用它建库、跑真实的 V1..V14 迁移：配置里那个参数本身就是被测对象。
 * 为了让「参数在不在」真的能改变结果，下面每条并发用例都让 T1 <b>持锁 3 秒</b>
 * （{@link #HOLD_MS}，大于 H2 默认的 ~2 秒、小于配置的 10 秒）——持锁时间太短的话，
 * 默认配置下 T2 也能等到，用例就测不出参数有没有生效（这一点是踩过的坑：最初的版本只持锁 300ms，
 * 拿掉 {@code LOCK_TIMEOUT} 依然全绿）。</p>
 *
 * <p>用真实迁移而不是手写建表：V14 的索引定义（可空列 + 普通唯一索引）就是要验的东西，
 * 手抄一份 DDL 等于把被测对象换成了测试自己的副本。</p>
 */
class AlarmConcurrencySemanticsTest {

    /**
     * T1 持锁时长：必须大于 H2 默认锁等待（约 2000ms）、小于配置的 {@code LOCK_TIMEOUT}（10000ms），
     * 参数是否生效才会真正改变用例结果。见类注释。
     */
    private static final long HOLD_MS = 3000L;

    private static String url;

    @BeforeAll
    static void migrate() throws Exception {
        // 换一个库名：H2 的 mem 库带 DB_CLOSE_DELAY=-1，会与其他测试类（@SpringBootTest 用的是
        // mem:monitor）共享同一个实例。共库会让本类的断言受别处的种子数据影响，且执行顺序不定。
        url = configuredH2Url().replaceFirst("mem:[^;]+", "mem:alarm-concurrency");
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    /** 取 {@code application.yml} 里真正生效的 H2 URL（含 LOCK_TIMEOUT），而不是在测试里另抄一份。 */
    private static String configuredH2Url() throws Exception {
        try (var in = AlarmConcurrencySemanticsTest.class.getResourceAsStream("/application.yml")) {
            assertTrue(in != null, "读不到 application.yml");
            String yml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("url:\\s*(jdbc:h2:[^\\s]+)").matcher(yml);
            assertTrue(m.find(), "application.yml 里找不到 H2 数据源 URL");
            return m.group(1);
        }
    }

    // ---------- ① 未解除唯一键 ----------

    /** 同一键的第二条未解除警情必须被唯一索引拒绝——这是多实例部署下唯一还成立的保证。 */
    @Test
    void onlyOneOpenRowPerKey() throws Exception {
        try (Connection c = open()) {
            long id = insertOpenAlarm(c, 9001L, "defo_mm", "P:9001:defo_mm");
            assertTrue(id > 0);

            SQLException e = assertThrowsSql(() -> insertOpenAlarm(c, 9001L, "defo_mm", "P:9001:defo_mm"));
            assertTrue(isUniqueViolation(e),
                    "第二条同键未解除警情必须撞唯一约束(23505)，实际是: " + e);
        }
    }

    /**
     * 终态行（{@code open_key IS NULL}）不受唯一性约束，可以有很多条。
     * 这条与上一条合起来才是「可空列 + 普通唯一索引」要表达的语义：
     * 两个库都把多个 NULL 视为互不相同。若哪天有人把索引改成非空列上的唯一键，
     * 这条会红——而那时该测点第二条警情无论解除与否都建不出来了。
     */
    @Test
    void closedRowsAreNotConstrained() throws Exception {
        try (Connection c = open()) {
            insertClosedAlarm(c, 9002L, "defo_mm");
            insertClosedAlarm(c, 9002L, "defo_mm");
            insertClosedAlarm(c, 9002L, "defo_mm");
            assertEquals(3, countClosed(c, 9002L, "defo_mm"), "多条 open_key 为 NULL 的行必须共存");
        }
    }

    /**
     * 关闭必须**释放键位**：置 NULL 之后同一键可以再次插入。
     * <p>这正是四条关闭路径都必须置 {@code open_key = NULL} 的原因——不置的话那条警情会永久占位，
     * 该测点该测项此后所有警情都会被唯一索引挡在门外（僵尸警情最终表现为「这个测项不再报警」）。</p>
     */
    @Test
    void closingReleasesTheKey() throws Exception {
        try (Connection c = open()) {
            long id = insertOpenAlarm(c, 9003L, "defo_mm", "P:9003:defo_mm");
            // 只置 NULL 不置 NULL 的写法（updateById 的默认行为）复现不出来，这里直接写 SQL：
            // 要验的是「键位释放后能复用」这个数据库层结论，不是 MyBatis-Plus 的字段跳过策略
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE alarm SET status = 'RESOLVED', open_key = NULL WHERE id = ?")) {
                ps.setLong(1, id);
                assertEquals(1, ps.executeUpdate());
            }
            long again = insertOpenAlarm(c, 9003L, "defo_mm", "P:9003:defo_mm");
            assertTrue(again > id, "键位释放后应能再开一条: " + again);
        }
    }

    // ---------- ② 带前置条件的 UPDATE（CAS） ----------

    /**
     * 并发 CAS 的核心断言：T2 用**过期**的状态做前置条件时，必须等到 T1 提交、然后返回 0 行，
     * 而不是把 T1 的写入覆盖掉、也不是抛锁超时。
     *
     * <p>这是 {@code AlarmService.act} 返回 409、以及引擎升级/解除跳过写入的依据。
     * 若 H2 在等锁超时后直接抛 {@code JdbcSQLTimeoutException(50200)}（拿掉
     * {@code LOCK_TIMEOUT=10000} 后的默认行为），本用例会红——那说明本地测出来的并发行为
     * 与生产不是一回事，后续所有并发断言都不可信。</p>
     */
    @Test
    void casWithStaleStatusWritesNothing() throws Exception {
        long id;
        try (Connection c = open()) {
            id = insertOpenAlarm(c, 9004L, "defo_mm", "P:9004:defo_mm");
        }

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection t1 = open(); Connection t2 = open()) {
            t1.setAutoCommit(false);
            t2.setAutoCommit(false);

            // T1：把 PENDING -> CONFIRMED，**先不提交**，此时它持有该行的写锁
            try (PreparedStatement ps = t1.prepareStatement(
                    "UPDATE alarm SET status = 'CONFIRMED' WHERE id = ?")) {
                ps.setLong(1, id);
                assertEquals(1, ps.executeUpdate());
            }

            // T2：拿过期的 PENDING 做前置条件。它会阻塞在行锁上，等 T1 提交
            Future<Integer> casRows = pool.submit(() -> {
                try (PreparedStatement ps = t2.prepareStatement(
                        "UPDATE alarm SET status = 'PROCESSING' WHERE id = ? AND status = 'PENDING'")) {
                    ps.setLong(1, id);
                    return ps.executeUpdate();
                }
            });
            Thread.sleep(HOLD_MS);
            assertFalse(casRows.isDone(), "T2 应当阻塞在 T1 的行锁上并等满 " + HOLD_MS
                    + "ms。10 秒内就结束说明它没等锁——多半是 application.yml 里的"
                    + " LOCK_TIMEOUT=10000 被去掉了（H2 默认约 2 秒即抛 50200）");

            t1.commit();

            assertEquals(0, casRows.get(10, TimeUnit.SECONDS).intValue(),
                    "T1 提交后 T2 的前置条件已不成立，应当返回 0 行（而不是覆盖写入或抛锁超时）");
            t2.rollback();

            // 终态仍是 T1 写的那一版——T2 没有覆盖它
            assertEquals("CONFIRMED", statusOf(id), "T2 不得覆盖 T1 的写入");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 并发插入同一 {@code open_key}：后到的那条必须撞**唯一约束**（23505），
     * 而不是撞**锁超时**（50200）。
     *
     * <p>这条是 V14 的兜底本身——多实例部署下没有进程内锁可用，唯一索引是最后一层也是唯一
     * 在跨进程时仍成立的一层。它必须报「重复」，因为调用方（接入流程）要能把它与
     * 「数据库卡住」区分开：前者是预期内的并发结果，后者是故障。</p>
     */
    @Test
    void concurrentInsertOfSameKeyReportsDuplicateNotLockTimeout() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection t1 = open(); Connection t2 = open()) {
            t1.setAutoCommit(false);
            t2.setAutoCommit(false);

            try (PreparedStatement ps = t1.prepareStatement(
                    "INSERT INTO alarm (alarm_type, point_id, metric_code, open_key, alarm_level, status,"
                            + " triggered_at, created_at, updated_at)"
                            + " VALUES ('POINT', 9006, 'defo_mm', 'P:9006:defo_mm', 'warning', 'PENDING',"
                            + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
                ps.executeUpdate();
            }

            Future<SQLException> failure = pool.submit(() -> {
                try (PreparedStatement ps = t2.prepareStatement(
                        "INSERT INTO alarm (alarm_type, point_id, metric_code, open_key, alarm_level, status,"
                                + " triggered_at, created_at, updated_at)"
                                + " VALUES ('POINT', 9006, 'defo_mm', 'P:9006:defo_mm', 'warning', 'PENDING',"
                                + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
                    ps.executeUpdate();
                    return null;              // 没抛异常 = 约束没生效，交给断言报错
                } catch (SQLException e) {
                    return e;
                }
            });
            Thread.sleep(HOLD_MS);
            assertFalse(failure.isDone(), "T2 应当阻塞在 T1 未提交的插入上并等满 " + HOLD_MS
                    + "ms；提前结束说明没等锁，见 application.yml 的 LOCK_TIMEOUT=10000");

            t1.commit();

            SQLException e = failure.get(10, TimeUnit.SECONDS);
            assertTrue(e != null, "第二条同键未解除警情必须被唯一索引拒绝，但它插入成功了");
            assertTrue(isUniqueViolation(e),
                    "必须是唯一约束冲突(23505)。拿到锁超时(50200)说明 H2 没等到 T1 提交，"
                            + "本地就测不到 V14 的兜底语义，实际是: " + e);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 对照用例：前置条件**成立**时 CAS 必须正常写入 1 行。
     * <p>没有这一条的话，「返回 0 行」这个断言可以被一个「永远更新不到」的实现轻松满足。</p>
     */
    @Test
    void casWithMatchingStatusWritesOneRow() throws Exception {
        long id;
        try (Connection c = open()) {
            id = insertOpenAlarm(c, 9005L, "defo_mm", "P:9005:defo_mm");
        }
        try (Connection c = open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE alarm SET status = 'CONFIRMED' WHERE id = ? AND status = 'PENDING'")) {
                ps.setLong(1, id);
                assertEquals(1, ps.executeUpdate());
            }
            assertEquals("CONFIRMED", statusOf(id));
        }
    }

    // ---------- 辅助 ----------

    private static Connection open() throws SQLException {
        Connection c = DriverManager.getConnection(url, "sa", "");
        c.setAutoCommit(true);
        return c;
    }

    private static long insertOpenAlarm(Connection c, long pointId, String metric, String openKey)
            throws SQLException {
        return insert(c, pointId, metric, openKey, "PENDING");
    }

    private static void insertClosedAlarm(Connection c, long pointId, String metric) throws SQLException {
        insert(c, pointId, metric, null, "RESOLVED");
    }

    private static long insert(Connection c, long pointId, String metric, String openKey, String status)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO alarm (alarm_type, point_id, metric_code, open_key, alarm_level, status,"
                        + " triggered_at, created_at, updated_at)"
                        + " VALUES ('POINT', ?, ?, ?, 'warning', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,"
                        + " CURRENT_TIMESTAMP)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, pointId);
            ps.setString(2, metric);
            ps.setString(3, openKey);
            ps.setString(4, status);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                assertTrue(keys.next(), "没有拿到自增主键");
                return keys.getLong(1);
            }
        }
    }

    private static String statusOf(long id) throws SQLException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("SELECT status FROM alarm WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private static int countClosed(Connection c, long pointId, String metric) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM alarm WHERE point_id = ? AND metric_code = ? AND open_key IS NULL")) {
            ps.setLong(1, pointId);
            ps.setString(2, metric);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static SQLException assertThrowsSql(SqlCall call) {
        try {
            call.run();
        } catch (SQLException e) {
            return e;
        }
        throw new AssertionError("预期抛 SQLException（唯一约束），但没有抛");
    }

    /** 唯一约束冲突的判定：错误码 23505（两个库一致），另接受 H2 的 50200 以便把失败原因说清楚。 */
    private static boolean isUniqueViolation(SQLException e) {
        for (SQLException cur = e; cur != null; cur = cur.getNextException()) {
            if (cur.getErrorCode() == 23505) {
                return true;
            }
        }
        return false;
    }

    @FunctionalInterface
    private interface SqlCall {
        void run() throws SQLException;
    }
}
