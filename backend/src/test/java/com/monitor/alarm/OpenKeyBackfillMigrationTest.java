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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V16（{@code backfill_alarm_open_key}）的行为取证。
 *
 * <p>为什么必须有这么一个测试：V16 是**唯一**一个会去改存量警情数据的迁移——它会关掉警情、
 * 释放键位、给行打键。而它要处理的那几种形状（回滚窗口里攒出的重复、旧镜像 updateById 留下的
 * 僵尸键位、rule_id 悬空的测点警情）在任何一个干净库里都**不存在**，</p>
 *
 * <blockquote>「在生产上跑一遍看看」不是验证方式：V16 一旦撞唯一索引就是整个迁移失败、
 * 应用起不来（迁移是原子的），而那正是它要防的事。</blockquote>
 *
 * <p>所以做法与 {@link AlarmConcurrencySemanticsTest} 一致：用 {@code application.yml} 里真正生效的
 * H2 配置建一个**独立库**，先由 Flyway 迁到 V15（= V16 上线前的库），用原生 JDBC 摆出上面那些形状，
 * 再迁到 V16，然后逐条断言。用真实迁移而不是手抄一份 SQL：被打键、被收敛的就是 V16 自己。</p>
 *
 * <p><b>只有一个 {@code @Test}，且顺序即语义</b>：迁移在一个库上只跑一次，中途插入各种形状再让它跑，
 * 才是它真实的工作方式。拆成多个用例反而要引入 {@code @TestMethodOrder}（而 V16 已经被应用过之后
 * 是无法回退到 V15 再跑一遍的），那种顺序依赖比一个长用例更脆。</p>
 */
class OpenKeyBackfillMigrationTest {

    /** 夹具里的规则 id：不与 V2/V4 的种子规则（1000 起）撞号。 */
    private static final long LIVE_RULE_ID = 9900L;
    /** 一个**不存在**的规则 id，用来复现「AlarmRuleService.delete 是物理删除」留下的悬空行。 */
    private static final long DANGLING_RULE_ID = 9899L;

    private static String url;

    @BeforeAll
    static void migrateToV15() throws Exception {
        url = configuredH2Url().replaceFirst("mem:[^;]+", "mem:alarm-backfill");
        flyway("15").migrate();
    }

    @Test
    void backfillIsSafeAndComplete() throws Exception {
        // ---------- 夹具：V16 上线前一个「回滚过旧镜像」的库里可能出现的各种形状 ----------
        long devLive;      // 普通设备警情，只是没打键（用户现场那两行就是这一类）
        long ptPlain;      // 普通测点警情，metric_code 本来就有
        long ptFromRule;   // 测点警情，metric_code 要靠 ① 从规则补
        long ptDangling;   // 测点警情，rule_id 悬空 -> 补不出测项 -> 打不出键（必须保持 NULL）
        long devTerminal;  // 终态行，本来就没有键 -> 不参与唯一性，也不该被写
        long devKeyed;     // 已经打过键的未解除行 -> 一个字节都不该动
        long dupFirst;     // ③（甲）：同键三连的第一条，应当留下并拿到键
        long dupSecond;    //            第二条，应当被关闭 + 留痕
        long dupThird;     //            第三条，同上
        long zombie;       // ②：终态行却占着键位（旧镜像 updateById 关的，没释放键）
        long devAfterZombie; //   同一键位的未解除空键行，② 释放后应当由 ⑤ 拿到键
        long holder;       // ④（乙）：未解除且已打键的占位者，保持不动
        long loser;        //   同一键位的未解除空键行，应当让位（被关闭 + 留痕）

        try (Connection c = open()) {
            insertRule(c, LIVE_RULE_ID, "defo_mm");

            devLive = insertAlarm(c, "DEVICE", null, 9201L, null, "OFFLINE", null, null, "PENDING");
            ptPlain = insertAlarm(c, "POINT", 9202L, null, LIVE_RULE_ID, null, "defo_mm", null, "PENDING");
            ptFromRule = insertAlarm(c, "POINT", 9203L, null, LIVE_RULE_ID, null, null, null, "PENDING");
            ptDangling = insertAlarm(c, "POINT", 9204L, null, DANGLING_RULE_ID, null, null, null, "PENDING");
            devTerminal = insertAlarm(c, "DEVICE", null, 9205L, null, "OFFLINE", null, null, "RESOLVED");
            devKeyed = insertAlarm(c, "DEVICE", null, 9206L, null, "OFFLINE", null, "D:9206:OFFLINE", "PENDING");

            dupFirst = insertAlarm(c, "DEVICE", null, 9207L, null, "OFFLINE", null, null, "PENDING");
            dupSecond = insertAlarm(c, "DEVICE", null, 9207L, null, "OFFLINE", null, null, "PENDING");
            dupThird = insertAlarm(c, "DEVICE", null, 9207L, null, "OFFLINE", null, null, "PENDING");

            zombie = insertAlarm(c, "DEVICE", null, 9208L, null, "OFFLINE", null, "D:9208:OFFLINE", "RESOLVED");
            devAfterZombie = insertAlarm(c, "DEVICE", null, 9208L, null, "OFFLINE", null, null, "PENDING");

            holder = insertAlarm(c, "DEVICE", null, 9209L, null, "OFFLINE", null, "D:9209:OFFLINE", "PENDING");
            loser = insertAlarm(c, "DEVICE", null, 9209L, null, "OFFLINE", null, null, "PENDING");
        }

        // ---------- 施加 V16 ----------
        // 撞唯一索引的话这里会直接抛，用例在下一行就红——那正是它要防的失败模式。
        flyway("16").migrate();

        // ---------- ⑤ 打键：键成分齐全的未解除行都该拿到键 ----------
        assertEquals("D:9201:OFFLINE", keyOf(devLive), "设备警情的键是 'D:<deviceId>:<reason>'");
        assertEquals("P:9202:defo_mm", keyOf(ptPlain), "测点警情的键是 'P:<pointId>:<metricCode>'");
        assertEquals("defo_mm", metricOf(ptFromRule), "① 应当从规则补出测项");
        assertEquals("P:9203:defo_mm", keyOf(ptFromRule), "补出测项之后才谈得上打键");

        // ---------- ① 只填空：悬空规则不得把已有的值抹成 NULL ----------
        assertNull(metricOf(ptDangling), "rule_id 悬空 -> 取不到测项，保持 NULL");
        assertNull(keyOf(ptDangling),
                "不许拼出 'P:9204:null' 这种字面量键——那会把一批互不相关的历史警情判成同一条");

        // ---------- 不该动的行 ----------
        assertNull(keyOf(devTerminal), "终态行不参与唯一性，保持 NULL");
        assertEquals("D:9206:OFFLINE", keyOf(devKeyed), "已打过键的行一个字节都不动");
        assertEquals("PENDING", statusOf(devKeyed));

        // ---------- ③（甲）收敛：同键位留最早一条，其余关闭并留痕 ----------
        assertEquals("D:9207:OFFLINE", keyOf(dupFirst), "留 id 最小的那条");
        assertEquals("PENDING", statusOf(dupFirst));
        assertEquals("RESOLVED", statusOf(dupSecond));
        assertEquals("RESOLVED", statusOf(dupThird));
        assertNull(keyOf(dupSecond), "被关掉的行不留键");
        assertNull(keyOf(dupThird));
        assertEquals(1, actionCount(dupSecond, "recover"), "关闭要补一条 recover 留痕");
        assertTrue(noteOf(dupSecond).contains("V16 迁移"), "留痕要说明是这次迁移关的: " + noteOf(dupSecond));
        assertEquals("system", operatorOf(dupSecond), "自动解除的操作者是 system");
        assertEquals(0, actionCount(dupFirst, "recover"), "留下的那条不该有解除留痕");

        // ---------- ② 释放僵尸键位 ----------
        assertNull(keyOf(zombie), "已解除的行占着键位会把该测点该测项后续所有警情挡在门外");
        assertEquals("D:9208:OFFLINE", keyOf(devAfterZombie), "键位释放后，那条未解除的警情应当拿到它");
        assertEquals("RESOLVED", statusOf(zombie), "② 只释放键位，不改状态");

        // ---------- ④（乙）让位给未解除的占位者 ----------
        assertEquals("D:9209:OFFLINE", keyOf(holder), "占位者保持不动");
        assertEquals("RESOLVED", statusOf(loser), "补键会撞索引的那条要关闭");
        assertEquals(1, actionCount(loser, "recover"));
        assertTrue(noteOf(loser).contains("V16 迁移"), "留痕要说明是这次迁移关的: " + noteOf(loser));

        // ---------- 全局后置条件（与部署文档 §3.5 的核对查询同一口径） ----------
        assertEquals(0, countSql("SELECT COUNT(*) FROM alarm WHERE open_key IS NOT NULL"
                        + " AND status IN ('RESOLVED', 'FALSE_ALARM')"),
                "② 之后不该再有僵尸键位");
        assertEquals(1, countSql("SELECT COUNT(*) FROM alarm WHERE open_key IS NULL"
                        + " AND status NOT IN ('RESOLVED', 'FALSE_ALARM')"),
                "唯一残留应当是那个键成分不全的行（悬空规则那条）");
        assertEquals(0, countSql("SELECT COUNT(*) FROM alarm a WHERE a.open_key IS NOT NULL"
                        + " AND EXISTS (SELECT 1 FROM alarm b WHERE b.id <> a.id"
                        + " AND b.open_key = a.open_key)"),
                "打键之后不得存在两条相同且非空的 open_key");

        // ---------- 幂等：再迁一次（target 16 已达成，Flyway 空转）不改变任何结论 ----------
        flyway("16").migrate();
        assertEquals("D:9201:OFFLINE", keyOf(devLive));
        assertEquals("RESOLVED", statusOf(dupSecond));
        assertEquals(1, actionCount(dupSecond, "recover"), "重跑不得再补一条留痕");
        assertEquals(1, countSql("SELECT COUNT(*) FROM alarm WHERE open_key IS NULL"
                + " AND status NOT IN ('RESOLVED', 'FALSE_ALARM')"));
    }

    // ---------- 辅助 ----------

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    /** 取 {@code application.yml} 里真正生效的 H2 URL，而不是在测试里另抄一份。 */
    private static String configuredH2Url() throws Exception {
        try (var in = OpenKeyBackfillMigrationTest.class.getResourceAsStream("/application.yml")) {
            assertTrue(in != null, "读不到 application.yml");
            String yml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("url:\\s*(jdbc:h2:[^\\s]+)").matcher(yml);
            assertTrue(m.find(), "application.yml 里找不到 H2 数据源 URL");
            return m.group(1);
        }
    }

    private static Connection open() throws SQLException {
        Connection c = DriverManager.getConnection(url, "sa", "");
        c.setAutoCommit(true);
        return c;
    }

    private static void insertRule(Connection c, long id, String metricCode) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO alarm_rule (id, name, metric_code, rule_type, operator, alarm_level) "
                        + "VALUES (?, 'V16 测试规则', ?, 'THRESHOLD', 'gte', 'warning')")) {
            ps.setLong(1, id);
            ps.setString(2, metricCode);
            ps.executeUpdate();
        }
    }

    private static long insertAlarm(Connection c, String type, Long pointId, Long deviceId, Long ruleId,
                                    String reason, String metric, String openKey, String status)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO alarm (alarm_type, point_id, device_id, rule_id, alarm_reason, metric_code,"
                        + " open_key, alarm_level, status, triggered_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, 'warning', ?, CURRENT_TIMESTAMP,"
                        + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, type);
            setNullable(ps, 2, pointId);
            setNullable(ps, 3, deviceId);
            setNullable(ps, 4, ruleId);
            ps.setString(5, reason);
            ps.setString(6, metric);
            ps.setString(7, openKey);
            ps.setString(8, status);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                assertTrue(keys.next(), "没有拿到自增主键");
                return keys.getLong(1);
            }
        }
    }

    private static void setNullable(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static String str(String column, long id) throws SQLException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("SELECT " + column + " FROM alarm WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "找不到警情 id=" + id);
                return rs.getString(1);
            }
        }
    }

    private static String keyOf(long id) throws SQLException {
        return str("open_key", id);
    }

    private static String statusOf(long id) throws SQLException {
        return str("status", id);
    }

    private static String metricOf(long id) throws SQLException {
        return str("metric_code", id);
    }

    private static int actionCount(long alarmId, String actionType) throws SQLException {
        return countSql("SELECT COUNT(*) FROM alarm_action WHERE alarm_id = " + alarmId
                + " AND action_type = '" + actionType + "'");
    }

    private static String noteOf(long alarmId) throws SQLException {
        return actionStr("note", lastActionId(alarmId));
    }

    private static String operatorOf(long alarmId) throws SQLException {
        return actionStr("operator", lastActionId(alarmId));
    }

    private static long lastActionId(long alarmId) throws SQLException {
        return countSqlLong("SELECT MAX(id) FROM alarm_action WHERE alarm_id = " + alarmId);
    }

    /** {@code column} 取 {@code alarm_action} 的列；直接用 id 定位那一条留痕。 */
    private static String actionStr(String column, long actionId) throws SQLException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT " + column + " FROM alarm_action WHERE id = ?")) {
            ps.setLong(1, actionId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "找不到留痕 id=" + actionId);
                return rs.getString(1);
            }
        }
    }

    private static int countSql(String sql) throws SQLException {
        return (int) countSqlLong(sql);
    }

    private static long countSqlLong(String sql) throws SQLException {
        try (Connection c = open();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), "计数查询没有返回行");
            return rs.getLong(1);
        }
    }
}
