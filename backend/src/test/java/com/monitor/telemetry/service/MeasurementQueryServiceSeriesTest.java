package com.monitor.telemetry.service;

import com.monitor.common.exception.BizException;
import com.monitor.project.entity.Metric;
import com.monitor.project.entity.MonitorPoint;
import com.monitor.project.mapper.MetricMapper;
import com.monitor.project.mapper.MonitorPointMapper;
import com.monitor.scope.service.DataScopeService;
import com.monitor.support.MybatisPlusLambdaCache;
import com.monitor.telemetry.dto.MeasurementBucket;
import com.monitor.telemetry.dto.PointSeriesVO;
import com.monitor.telemetry.entity.Measurement;
import com.monitor.telemetry.mapper.MeasurementMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * series 的规模上限与分桶路径（复查清单 P0-3）在**服务层**的行为。
 *
 * <p>纯函数那半边在 {@link SeriesWindowPolicyTest}；这里钉的是另外三件事：</p>
 * <ol>
 *   <li>{@code raw} 超过 5000 点必须 **400 而不是截断**——"少了后半段"的曲线在图上看不出来，
 *       那正是形变监测里最不能出的错；</li>
 *   <li>恰好 5000 点必须**放行**（多取一行的探测法容易在这里写成"≥ 上限就拒"）；</li>
 *   <li>{@code hour}/{@code day} 走 SQL 聚合（{@code averageByBucket}），
 *       不再把原始行拉进 JVM 分组——否则"分桶下沉"只是文档里的一句话。</li>
 * </ol>
 */
class MeasurementQueryServiceSeriesTest {

    private static final Long POINT_ID = 1L;

    private MeasurementMapper mapper;
    private MonitorPointMapper pointMapper;
    private MetricMapper metricMapper;
    private DataScopeService dataScope;
    private MeasurementBaselineService baselineService;
    private MeasurementQueryService service;

    @BeforeAll
    static void warmLambdaCaches() {
        MybatisPlusLambdaCache.warm(Measurement.class, MonitorPoint.class, Metric.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(MeasurementMapper.class);
        pointMapper = mock(MonitorPointMapper.class);
        metricMapper = mock(MetricMapper.class);
        dataScope = mock(DataScopeService.class);
        baselineService = mock(MeasurementBaselineService.class);
        service = new MeasurementQueryService(mapper, pointMapper, metricMapper, dataScope, baselineService);

        MonitorPoint p = new MonitorPoint();
        p.setId(POINT_ID);
        p.setCode("P-TEST");
        when(pointMapper.selectById(POINT_ID)).thenReturn(p);
        when(metricMapper.selectOne(any())).thenReturn(null);
        when(baselineService.inWindow(any(), any(), any())).thenReturn(List.of());
    }

    @Test
    void rawOverThePointLimitIsRejectedNotTruncated() {
        when(mapper.selectList(any())).thenReturn(rows(SeriesWindowPolicy.MAX_RAW_POINTS + 1));

        assertThatThrownBy(() -> service.series(POINT_ID, null, null, null, "raw"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining(String.valueOf(SeriesWindowPolicy.MAX_RAW_POINTS));
    }

    @Test
    void rawExactlyAtTheLimitIsAccepted() {
        when(mapper.selectList(any())).thenReturn(rows(SeriesWindowPolicy.MAX_RAW_POINTS));

        PointSeriesVO vo = service.series(POINT_ID, null, null, null, "raw");

        assertThat(vo.getPoints()).hasSize(SeriesWindowPolicy.MAX_RAW_POINTS);
    }

    @Test
    void hourGranularityAggregatesInSql() {
        List<MeasurementBucket> buckets = new ArrayList<>();
        MeasurementBucket b = new MeasurementBucket();
        b.setBucketTime(LocalDateTime.of(2026, 9, 18, 10, 0));
        b.setBucketValue(new BigDecimal("1.23456"));
        buckets.add(b);
        when(mapper.averageByBucket(eq(POINT_ID), eq("defo_mm"), any(), any(), anyBoolean()))
                .thenReturn(buckets);

        PointSeriesVO vo = service.series(POINT_ID, null, null, null, "hour");

        assertThat(vo.getPoints()).hasSize(1);
        assertThat(vo.getPoints().get(0).getV()).isEqualTo(1.2346);   // 与改造前同一套四舍五入
        verify(mapper, never()).selectList(any());
        verify(mapper).averageByBucket(eq(POINT_ID), eq("defo_mm"), any(), any(), eq(false));
    }

    @Test
    void dayGranularityAsksForDayBuckets() {
        when(mapper.averageByBucket(any(), any(), any(), any(), anyBoolean())).thenReturn(List.of());

        service.series(POINT_ID, "defo_mm", null, null, "day");

        verify(mapper).averageByBucket(eq(POINT_ID), eq("defo_mm"), any(), any(), eq(true));
    }

    /** 不传窗口时响应必须回显实际用的窗口——否则"为什么只有这些点"在接口上无法自证。 */
    @Test
    void responseEchoesTheResolvedWindow() {
        when(mapper.selectList(any())).thenReturn(List.of());

        PointSeriesVO vo = service.series(POINT_ID, null, null, null, "raw");

        assertThat(vo.getWindowDefaulted()).isTrue();
        assertThat(vo.getFrom()).isNotBlank();
        assertThat(vo.getTo()).isNotBlank();
        assertThat(vo.getUnit()).isEqualTo("mm");   // 档案里没有 unit 时的兜底值
    }

    private static List<Measurement> rows(int n) {
        List<Measurement> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Measurement m = new Measurement();
            m.setCollectTime(LocalDateTime.of(2026, 9, 18, 0, 0).plusSeconds(i));
            m.setMeasureValue((double) i);
            list.add(m);
        }
        return list;
    }
}
