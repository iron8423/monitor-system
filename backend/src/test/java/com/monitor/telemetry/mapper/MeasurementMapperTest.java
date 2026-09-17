package com.monitor.telemetry.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.monitor.support.MybatisPlusLambdaCache;
import com.monitor.telemetry.entity.Measurement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「当前值」查询口径（清单 03 遗留）的判据。
 *
 * <p>为什么用断言 SQL 片段这种方式测：{@code latestRowsOfPoints} 是 {@code @Select} 注解里的
 * 相关子查询、{@code latestRowOf} 是 default 方法拼 wrapper，两者没有可注入的接缝，
 * 单测里唯一能看到的产物就是拼出来的 SQL。而这里要钉住的恰恰就是「SQL 拼出来长什么样」——
 * 尤其是 {@code ingest_mode} 过滤有没有、以及它有没有跑到 {@code ORDER BY} **后面**去。</p>
 *
 * <p>后者不是假想：MyBatis-Plus 按调用顺序拼片段，{@code orderByDesc} 之后再 {@code .eq(...)}
 * 会拼到 ORDER BY 后面，SQL 直接语法错误（线上第一次查询就炸）。{@code MeasurementMapper}
 * 的类注释专门为这条写了警告，这里把它变成会红的断言。</p>
 *
 * <p>清单第 10 条给这个口径加了第三个从句（{@code collect_time} 不超前于现在），
 * 同样有三件事要钉：条件在不在、值有没有真的传进去、位置在不在 ORDER BY 之前。</p>
 */
class MeasurementMapperTest {

    /** 当前值的时间上界。具体值不参与断言，只是让 wrapper 有一个非 null 的绑定参数。 */
    private static final LocalDateTime CEILING = LocalDateTime.of(2026, 9, 17, 12, 0);

    @BeforeAll
    static void warmLambdaCaches() {
        // wrapper 的列名解析是急切的，纯单测没有 MyBatis-Plus 启动流程，需要手工预热。
        MybatisPlusLambdaCache.warm(Measurement.class);
    }

    @Test
    void latestRowOfOnlyConsidersRealtimeRows() {
        LambdaQueryWrapper<Measurement> wrapper = latestRowOf(3L, "defo_mm");

        assertTrue(wrapper.getSqlSegment().contains("ingest_mode"),
                "必须过滤 ingest_mode，否则补报的历史行会冒充当前值: " + wrapper.getSqlSegment());
        // 值走绑定参数，不在 SQL 文本里——要断言的是绑定的那个值本身。
        assertTrue(wrapper.getParamNameValuePairs().containsValue("REALTIME"),
                "过滤值必须是 REALTIME: " + wrapper.getParamNameValuePairs());
    }

    @Test
    void latestRowOfKeepsConditionsBeforeOrderBy() {
        String sql = latestRowOfSql(3L, "defo_mm");

        int orderBy = sql.toUpperCase().indexOf("ORDER BY");
        assertTrue(orderBy > 0, "应有 ORDER BY: " + sql);
        int ingestMode = sql.toUpperCase().indexOf("INGEST_MODE");
        assertTrue(ingestMode > 0 && ingestMode < orderBy,
                "ingest_mode 条件必须排在 ORDER BY 之前，否则拼到排序之后是语法错误: " + sql);
    }

    @Test
    void latestRowOfOrdersByCollectTimeThenId() {
        String sql = latestRowOfSql(3L, "defo_mm").toUpperCase();

        // id 兜底不可省：同刻两行时只按 collect_time 排序，取哪一行由执行计划决定。
        assertTrue(sql.contains("COLLECT_TIME DESC") && sql.contains("ID DESC"),
                "排序必须是 collect_time DESC, id DESC: " + sql);
    }

    @Test
    void latestRowOfWithoutMetricCodeDoesNotFilterOnIt() {
        // metricCode 传 null 表示不限测项（快照批量取「该点最新一行」用）。
        String withMetric = latestRowOfSql(3L, "defo_mm").toUpperCase();
        String withoutMetric = latestRowOfSql(3L, null).toUpperCase();

        assertTrue(withMetric.contains("METRIC_CODE"), "指定测项时应带上 metric_code 条件: " + withMetric);
        assertTrue(!withoutMetric.contains("METRIC_CODE"), "不限测项时不该出现 metric_code 条件: " + withoutMetric);
        assertTrue(withoutMetric.contains("INGEST_MODE"), "不限测项也要过滤 ingest_mode: " + withoutMetric);
    }

    @Test
    void latestRowsOfPointsSubqueryOnlyConsidersRealtimeRows() {
        String sql = annotationSql();

        assertTrue(sql.contains("m2.ingest_mode"), "子查询里必须过滤 ingest_mode: " + sql);
        assertTrue(sql.contains("REALTIME"), "过滤值必须是 REALTIME: " + sql);
        // 外层靠 m.id = (子查询) 命中，不必也不该再过滤一次（多一个条件只多一次白扫）。
        assertEquals(1, sql.split("ingest_mode", -1).length - 1,
                "ingest_mode 只应出现在子查询里一次: " + sql);
    }

    @Test
    void latestRowOfCapsCollectTimeAtCeiling() {
        LambdaQueryWrapper<Measurement> wrapper = latestRowOf(3L, "defo_mm");
        String sql = wrapper.getSqlSegment().toUpperCase();

        assertTrue(sql.contains("COLLECT_TIME <="),
                "必须卡 collect_time 上界，否则未来时间的行会永久钉住「当前值」: " + sql);
        assertTrue(wrapper.getParamNameValuePairs().containsValue(CEILING),
                "上界必须走绑定参数传进去，且值就是调用方给的那个: " + wrapper.getParamNameValuePairs());
    }

    @Test
    void latestRowOfKeepsCeilingBeforeOrderBy() {
        // 与 ingest_mode 同一个坑：条件跑到 ORDER BY 后面就是语法错误。这条把「位置」也钉住，
        // 因为新加的条件是照着既有那行抄的，抄错位置的可能性不比抄错列名低。
        String sql = latestRowOfSql(3L, "defo_mm").toUpperCase();

        int orderBy = sql.indexOf("ORDER BY");
        int ceiling = sql.indexOf("COLLECT_TIME <=");
        assertTrue(orderBy > 0 && ceiling > 0, "应有 ORDER BY 与 collect_time 上界: " + sql);
        assertTrue(ceiling < orderBy, "collect_time 上界必须排在 ORDER BY 之前: " + sql);
    }

    @Test
    void latestRowsOfPointsSubqueryCapsCollectTimeAtCeiling() {
        String sql = annotationSql();

        assertTrue(sql.contains("m2.collect_time &lt;= #{ceiling}"),
                "子查询里必须卡 collect_time 上界: " + sql);
    }

    // ---------- 夹具 ----------

    /** 经 {@link MeasurementMapper#latestRowOf} 真实实现拼出的 wrapper。 */
    private static LambdaQueryWrapper<Measurement> latestRowOf(Long pointId, String metricCode) {
        // latestRowOf 是 default 方法、且不碰任何抽象方法（只 new 一个 wrapper 返回），
        // 所以 CALLS_REAL_METHODS 的 mock 足以执行到真实实现，不需要为 20 多个抽象方法写空壳。
        MeasurementMapper mapper = Mockito.mock(MeasurementMapper.class, Answers.CALLS_REAL_METHODS);
        return mapper.latestRowOf(pointId, metricCode, CEILING);
    }

    /** 同上，只取 SQL 片段（含 WHERE 与 ORDER BY）。 */
    private static String latestRowOfSql(Long pointId, String metricCode) {
        return latestRowOf(pointId, metricCode).getSqlSegment();
    }

    /** {@code latestRowsOfPoints} 的注解原文（该方法是注解式 SQL，取不到 wrapper）。 */
    private static String annotationSql() {
        try {
            return MeasurementMapper.class.getMethod("latestRowsOfPoints", List.class, LocalDateTime.class)
                    .getAnnotation(org.apache.ibatis.annotations.Select.class).value()[0];
        } catch (NoSuchMethodException e) {
            throw new AssertionError("latestRowsOfPoints 签名变了，本测试需要同步", e);
        }
    }
}
