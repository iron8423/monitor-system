package com.monitor.telemetry.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.monitor.asset.dto.PointSourceRow;
import com.monitor.asset.mapper.DevicePointMapper;
import com.monitor.project.entity.Metric;
import com.monitor.project.entity.MonitorPoint;
import com.monitor.project.mapper.MetricMapper;
import com.monitor.project.mapper.MonitorPointMapper;
import com.monitor.scope.service.DataScopeService;
import com.monitor.support.MybatisPlusLambdaCache;
import com.monitor.telemetry.dto.PointLatestVO;
import com.monitor.telemetry.entity.Measurement;
import com.monitor.telemetry.mapper.MeasurementMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多源权威（复查清单 P1-2）的取值判据：**优先级最小的来源里、时间最新的一条**。
 *
 * <p>为什么必须单独钉：原判据与来源无关（纯按时间），两台雷达看同一个点时，
 * 采样更慢或链路更慢的那台会持续盖掉快的那台——界面上只有一个数，看不出来。
 * 而"改判"这件事很容易写反（越小越优先 / 只在严格更优先时才改判 / 默认档不动既有行为），
 * 这三条都在下面。</p>
 */
class MeasurementQueryServiceSourcePriorityTest {

    private static final Long POINT_ID = 1L;
    private static final String METRIC = "defo_mm";

    private MeasurementMapper mapper;
    private MonitorPointMapper pointMapper;
    private MetricMapper metricMapper;
    private DataScopeService dataScope;
    private MeasurementBaselineService baselineService;
    private DevicePointMapper devicePointMapper;
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
        devicePointMapper = mock(DevicePointMapper.class);
        service = new MeasurementQueryService(mapper, pointMapper, metricMapper, dataScope,
                baselineService, devicePointMapper);

        MonitorPoint p = new MonitorPoint();
        p.setId(POINT_ID);
        p.setCode("P-SRC");
        when(pointMapper.selectById(POINT_ID)).thenReturn(p);
        when(metricMapper.selectOne(any())).thenReturn(null);
        when(baselineService.inWindow(any(), any(), any())).thenReturn(List.of());
        when(mapper.latestRowOf(any(), any(), any())).thenReturn(new LambdaQueryWrapper<>());
    }

    /** 权威来源（优先级 10）比"按时间最新"的那台（默认 100）**慢**，但值仍然取它的。 */
    @Test
    void authoritativeSourceWinsEvenWhenItsRowIsOlder() {
        when(devicePointMapper.selectSourcesByPointId(POINT_ID)).thenReturn(List.of(
                source(1L, "radar-north", 10), source(2L, "radar-south", 100)));
        Measurement newer = row(2L, "radar-south", "2026-09-20T12:00:00", 9.9);
        Measurement authoritative = row(1L, "radar-north", "2026-09-20T11:59:00", 1.5);
        when(mapper.selectOne(any())).thenReturn(newer, authoritative);
        // 测项值来自同一 message_id 的兄弟行（selectList），只桩 selectOne 会得到空 latest
        when(mapper.selectList(any())).thenReturn(new ArrayList<>(List.of(authoritative)));

        PointLatestVO vo = service.latest(POINT_ID);

        assertThat(vo.getLatest().get(METRIC)).isEqualTo(1.5);
        assertThat(vo.getSourceDeviceCode()).isEqualTo("radar-north");
        assertThat(vo.getSourcePriority()).isEqualTo(10);
        // 多来源时带上完整清单，界面才能解释"为什么是这个数"
        assertThat(vo.getSources()).hasSize(2);
    }

    /** 权威来源这一刻没有数据（比如刚上电）→ 退回"按时间最新"的那台，而不是给空值。 */
    @Test
    void fallsBackWhenAuthoritativeSourceHasNoRowYet() {
        when(devicePointMapper.selectSourcesByPointId(POINT_ID)).thenReturn(List.of(
                source(1L, "radar-north", 10), source(2L, "radar-south", 100)));
        Measurement newer = row(2L, "radar-south", "2026-09-20T12:00:00", 9.9);
        when(mapper.selectOne(any())).thenReturn(newer, null);
        when(mapper.selectList(any())).thenReturn(new ArrayList<>(List.of(newer)));

        PointLatestVO vo = service.latest(POINT_ID);

        assertThat(vo.getLatest().get(METRIC)).isEqualTo(9.9);
        assertThat(vo.getSourceDeviceCode()).isEqualTo("radar-south");
        assertThat(vo.getSourcePriority()).isEqualTo(100);
    }

    /** 全都还是默认档（100）→ **完全按时间**，且不多发一条查询：既有行为一字不变。 */
    @Test
    void allDefaultPrioritiesKeepTheOldTimeOnlyRule() {
        when(devicePointMapper.selectSourcesByPointId(POINT_ID)).thenReturn(List.of(
                source(1L, "radar-north", 100), source(2L, "radar-south", 100)));
        when(mapper.selectOne(any())).thenReturn(row(2L, "radar-south", "2026-09-20T12:00:00", 9.9));

        PointLatestVO vo = service.latest(POINT_ID);

        assertThat(vo.getSourceDeviceCode()).isEqualTo("radar-south");
        verify(mapper, times(1)).selectOne(any());   // 只有那一条"按时间最新"
    }

    /**
     * 未绑定的来源按**默认优先级 100**参与（不被忽略）：设备直接给某点上报但没建绑定是允许的
     * （验收套件大量这么造数），踢出取值会变成"数据凭空消失"，只是它不享有更高权威。
     */
    @Test
    void unboundSourceCountsAsDefaultPriority() {
        when(devicePointMapper.selectSourcesByPointId(POINT_ID)).thenReturn(List.of(
                source(1L, "radar-north", 10)));
        Measurement fromUnbound = row(null, "radar-unbound", "2026-09-20T12:00:00", 8.8);
        Measurement authoritative = row(1L, "radar-north", "2026-09-20T11:00:00", 1.1);
        when(mapper.selectOne(any())).thenReturn(fromUnbound, authoritative);
        when(mapper.selectList(any())).thenReturn(new ArrayList<>(List.of(authoritative)));

        PointLatestVO vo = service.latest(POINT_ID);

        assertThat(vo.getSourceDeviceCode()).isEqualTo("radar-north");
        assertThat(vo.getLatest().get(METRIC)).isEqualTo(1.1);
    }

    private static PointSourceRow source(Long deviceId, String code, int priority) {
        PointSourceRow row = new PointSourceRow();
        row.setPointId(POINT_ID);
        row.setDeviceId(deviceId);
        row.setDeviceCode(code);
        row.setDeviceName(code);
        row.setSourcePriority(priority);
        row.setCalibrationStatus("ACTIVE");
        row.setLineOfSight(true);
        return row;
    }

    private static Measurement row(Long deviceId, String deviceCode, String collectTime, double value) {
        Measurement m = new Measurement();
        m.setPointId(POINT_ID);
        m.setDeviceId(deviceCode);
        m.setMetricCode(METRIC);
        m.setMessageId("msg-" + deviceCode);
        m.setMeasureValue(value);
        m.setCollectTime(LocalDateTime.parse(collectTime));
        m.setQuality("VALID");
        m.setIngestMode("REALTIME");
        return m;
    }
}
