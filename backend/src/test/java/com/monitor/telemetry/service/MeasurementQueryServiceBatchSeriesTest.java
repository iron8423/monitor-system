package com.monitor.telemetry.service;

import com.monitor.asset.mapper.DevicePointMapper;
import com.monitor.common.exception.BizException;
import com.monitor.project.entity.Metric;
import com.monitor.project.entity.MonitorPoint;
import com.monitor.project.mapper.MetricMapper;
import com.monitor.project.mapper.MonitorPointMapper;
import com.monitor.scope.service.DataScopeService;
import com.monitor.support.MybatisPlusLambdaCache;
import com.monitor.telemetry.dto.MeasurementPointBucket;
import com.monitor.telemetry.dto.PointSeriesBatchVO;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批量 series（复查清单 P2-4）在服务层的行为。
 *
 * <p>这个端点的全部价值就是"把 N 次请求压成一次"，所以断言的重点不是"能返回数据"，
 * 而是**查询次数**与**闸门**：</p>
 * <ol>
 *   <li>无论多少个测点，原始行查询只发**一次**（{@code times(1)}）——发两次就等于没省；</li>
 *   <li>重复 id 去重后只查一次、不重复返回；</li>
 *   <li>不存在的 / 不在数据范围内的点进 {@code skippedPointIds}，**不让整批失败**
 *       （单点端点是 404/403，批量照搬会让整屏曲线消失）；</li>
 *   <li>规模闸门与单点同口径：单点 5000 条、单批合计 20000 条、单请求 200 个点，
 *       超限都是 400 而不是静默截断；</li>
 *   <li>hour/day 仍然走 SQL 聚合（{@code averageByBucketForPoints}），不碰原始行查询。</li>
 * </ol>
 */
class MeasurementQueryServiceBatchSeriesTest {

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
        service = new MeasurementQueryService(mapper, pointMapper, metricMapper, dataScope,
                baselineService, mock(DevicePointMapper.class));
        when(dataScope.canSeePoint(any())).thenReturn(true);
        when(metricMapper.selectList(any())).thenReturn(List.of());
        when(baselineService.inWindowForPoints(anyList(), any(), any())).thenReturn(java.util.Map.of());
    }

    private void archive(Long... ids) {
        List<MonitorPoint> points = new ArrayList<>();
        for (Long id : ids) {
            MonitorPoint p = new MonitorPoint();
            p.setId(id);
            p.setCode("P-" + id);
            points.add(p);
        }
        when(pointMapper.selectByIds(anyList())).thenReturn(points);
    }

    private static Measurement row(long pointId, int minute, double value) {
        Measurement m = new Measurement();
        m.setPointId(pointId);
        m.setMetricCode("defo_mm");
        m.setCollectTime(LocalDateTime.of(2026, 9, 20, 10, 0).plusMinutes(minute));
        m.setMeasureValue(value);
        return m;
    }

    @Test
    void fetchesEveryPointInOneQuery() {
        archive(1L, 2L, 3L);
        when(mapper.batchRawRows(anyList(), eq("defo_mm"), any(), any(), anyInt()))
                .thenReturn(List.of(row(1L, 0, 1.0), row(2L, 0, 2.0), row(1L, 5, 1.5)));

        PointSeriesBatchVO vo = service.batchSeries(List.of(1L, 2L, 3L), null, null, null, null);

        verify(mapper, times(1)).batchRawRows(anyList(), any(), any(), any(), anyInt());
        assertThat(vo.getSeries()).hasSize(3);
        assertThat(vo.getSeries().get(0).getPointId()).isEqualTo(1L);
        assertThat(vo.getSeries().get(0).getPoints()).hasSize(2);
        assertThat(vo.getSeries().get(2).getPoints()).isEmpty();
        assertThat(vo.getWindowDefaulted()).isTrue();
    }

    @Test
    void duplicateIdsAreQueriedOnceAndReturnedOnce() {
        archive(1L, 2L);
        when(mapper.batchRawRows(anyList(), any(), any(), any(), anyInt())).thenReturn(List.of());

        PointSeriesBatchVO vo = service.batchSeries(List.of(1L, 2L, 1L, 2L, 1L), null, null, null, null);

        verify(mapper, times(1)).batchRawRows(eq(List.of(1L, 2L)), any(), any(), any(), anyInt());
        assertThat(vo.getSeries()).hasSize(2);
        assertThat(vo.getRequestedPoints()).isEqualTo(2);
    }

    @Test
    void invisibleAndMissingPointsAreSkippedNotFatal() {
        archive(1L, 2L);
        when(dataScope.canSeePoint(2L)).thenReturn(false);
        when(mapper.batchRawRows(anyList(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(row(1L, 0, 1.0)));

        // 2 号不可见、3 号不存在
        PointSeriesBatchVO vo = service.batchSeries(List.of(1L, 2L, 3L), null, null, null, null);

        assertThat(vo.getSeries()).extracting("pointId").containsExactly(1L);
        assertThat(vo.getSkippedPointIds()).containsExactly(2L, 3L);
        verify(mapper, times(1)).batchRawRows(eq(List.of(1L)), any(), any(), any(), anyInt());
    }

    @Test
    void allPointsSkippedReturnsEmptyInsteadOfError() {
        when(pointMapper.selectByIds(anyList())).thenReturn(List.of());
        PointSeriesBatchVO vo = service.batchSeries(List.of(9L), null, null, null, null);
        assertThat(vo.getSeries()).isEmpty();
        assertThat(vo.getSkippedPointIds()).containsExactly(9L);
        assertThat(vo.getNote()).contains("没有可返回的曲线");
        verify(mapper, never()).batchRawRows(anyList(), any(), any(), any(), anyInt());
    }

    @Test
    void singlePointOverRawLimitIsRejected() {
        archive(1L);
        List<Measurement> rows = new ArrayList<>();
        for (int i = 0; i <= SeriesWindowPolicy.MAX_RAW_POINTS; i++) {
            rows.add(row(1L, i, 1.0));
        }
        when(mapper.batchRawRows(anyList(), any(), any(), any(), anyInt())).thenReturn(rows);

        assertThatThrownBy(() -> service.batchSeries(List.of(1L), null, null, null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("测点 1")
                .hasMessageContaining(String.valueOf(SeriesWindowPolicy.MAX_RAW_POINTS));
    }

    @Test
    void exactlyAtRawLimitIsAccepted() {
        archive(1L);
        List<Measurement> rows = new ArrayList<>();
        for (int i = 0; i < SeriesWindowPolicy.MAX_RAW_POINTS; i++) {
            rows.add(row(1L, i, 1.0));
        }
        when(mapper.batchRawRows(anyList(), any(), any(), any(), anyInt())).thenReturn(rows);

        PointSeriesBatchVO vo = service.batchSeries(List.of(1L), null, null, null, null);
        assertThat(vo.getSeries().get(0).getPoints()).hasSize(SeriesWindowPolicy.MAX_RAW_POINTS);
    }

    /**
     * 合计预算：4 个点各 5000 行（都**没有**超过单点上限）+ 第 5 个点 1 行 = 20001 行。
     *
     * <p>构造刻意要让每个点各自合法，否则先触发的是单点那条闸门，
     * 这条"合计"的闸门就永远测不到（第一次就写成了这样）。</p>
     */
    @Test
    void batchTotalOverBudgetIsRejected() {
        archive(1L, 2L, 3L, 4L, 5L);
        List<Measurement> rows = new ArrayList<>();
        for (long id = 1; id <= 4; id++) {
            for (int i = 0; i < SeriesWindowPolicy.MAX_RAW_POINTS; i++) {
                rows.add(row(id, i, 1.0));
            }
        }
        rows.add(row(5L, 0, 1.0));
        when(mapper.batchRawRows(anyList(), any(), any(), any(), anyInt())).thenReturn(rows);

        assertThatThrownBy(() -> service.batchSeries(List.of(1L, 2L, 3L, 4L, 5L), null, null, null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("合计");
    }

    @Test
    void tooManyPointsInOneRequestIsRejected() {
        List<Long> ids = new ArrayList<>();
        for (long i = 1; i <= MeasurementQueryService.MAX_BATCH_POINTS + 1; i++) {
            ids.add(i);
        }
        assertThatThrownBy(() -> service.batchSeries(ids, null, null, null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining(String.valueOf(MeasurementQueryService.MAX_BATCH_POINTS));
        verify(mapper, never()).batchRawRows(anyList(), any(), any(), any(), anyInt());
    }

    @Test
    void emptyIdsIsRejected() {
        assertThatThrownBy(() -> service.batchSeries(List.of(), null, null, null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("pointIds");
    }

    @Test
    void hourlyGranularityAggregatesInSql() {
        archive(1L);
        MeasurementPointBucket bucket = new MeasurementPointBucket();
        bucket.setPointId(1L);
        bucket.setBucketTime(LocalDateTime.of(2026, 9, 20, 10, 0));
        bucket.setBucketValue(new BigDecimal("1.25"));
        when(mapper.averageByBucketForPoints(anyList(), any(), any(), any(), anyBoolean()))
                .thenReturn(List.of(bucket));

        PointSeriesBatchVO vo = service.batchSeries(List.of(1L), "defo_mm", null, null, "hour");

        verify(mapper, times(1)).averageByBucketForPoints(anyList(), any(), any(), any(), eq(false));
        verify(mapper, never()).batchRawRows(anyList(), any(), any(), any(), anyInt());
        assertThat(vo.getSeries().get(0).getPoints()).hasSize(1);
        assertThat(vo.getSeries().get(0).getPoints().get(0).getV()).isEqualTo(1.25);
    }

    /** 桶里没有 pointId、或桶内全为 NULL（数据库给 NULL 均值）的行必须被跳过，不能变成 0。 */
    @Test
    void bucketedRowsWithoutPointIdOrValueAreIgnored() {
        archive(1L);
        MeasurementPointBucket noPoint = new MeasurementPointBucket();
        noPoint.setBucketTime(LocalDateTime.of(2026, 9, 20, 10, 0));
        noPoint.setBucketValue(new BigDecimal("1.0"));
        MeasurementPointBucket noValue = new MeasurementPointBucket();
        noValue.setPointId(1L);
        noValue.setBucketTime(LocalDateTime.of(2026, 9, 20, 11, 0));
        when(mapper.averageByBucketForPoints(anyList(), any(), any(), any(), anyBoolean()))
                .thenReturn(List.of(noPoint, noValue));

        PointSeriesBatchVO vo = service.batchSeries(List.of(1L), null, null, null, "hour");
        assertThat(vo.getSeries().get(0).getPoints()).isEmpty();
    }
}
