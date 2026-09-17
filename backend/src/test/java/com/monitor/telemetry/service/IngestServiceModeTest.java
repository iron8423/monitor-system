package com.monitor.telemetry.service;

import com.monitor.alarm.service.AlarmEngine;
import com.monitor.asset.entity.Device;
import com.monitor.asset.entity.DevicePoint;
import com.monitor.asset.mapper.DeviceMapper;
import com.monitor.asset.mapper.DevicePointMapper;
import com.monitor.common.sse.SseBroadcaster;
import com.monitor.project.entity.Metric;
import com.monitor.project.entity.MonitorPoint;
import com.monitor.project.mapper.MetricMapper;
import com.monitor.project.mapper.MonitorPointMapper;
import com.monitor.scope.service.DataScopeService;
import com.monitor.support.MybatisPlusLambdaCache;
import com.monitor.telemetry.dto.IngestMessage;
import com.monitor.telemetry.dto.IngestMode;
import com.monitor.telemetry.dto.IngestRequest;
import com.monitor.telemetry.dto.IngestResult;
import com.monitor.telemetry.entity.Measurement;
import com.monitor.telemetry.mapper.MeasurementMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestServiceModeTest {

    @Mock private MeasurementMapper measurementMapper;
    @Mock private MonitorPointMapper pointMapper;
    @Mock private DeviceMapper deviceMapper;
    @Mock private DevicePointMapper devicePointMapper;
    @Mock private MetricMapper metricMapper;
    @Mock private AlarmEngine alarmEngine;
    @Mock private SseBroadcaster broadcaster;
    @Mock private DataScopeService dataScope;

    private IngestService service;
    private Device device;

    @BeforeAll
    static void warmLambdaCaches() {
        // ingest() 会构造 LambdaQueryWrapper，列名解析是急切的：没有 TableInfo 就没有 lambda
        // 列名缓存，wrapper 还没交给 mock 就抛 "can not find lambda cache"。纯单测没有
        // MyBatis-Plus 启动流程，这里手工预热。DevicePoint 当前因 strictContract=false 走不到，
        // 一并预热，避免以后打开严格契约时再踩一次。
        MybatisPlusLambdaCache.warm(MonitorPoint.class, Device.class, Metric.class,
                Measurement.class, DevicePoint.class);
    }

    @BeforeEach
    void setUp() {
        service = new IngestService(measurementMapper, pointMapper, deviceMapper, devicePointMapper,
                metricMapper, alarmEngine, broadcaster, dataScope);
        ReflectionTestUtils.setField(service, "strictContract", false);
        ReflectionTestUtils.setField(service, "maxBatchSize", 2000);

        MonitorPoint point = new MonitorPoint();
        point.setId(101L);
        point.setCode("P-01");
        point.setEnabled(true);
        device = new Device();
        device.setId(201L);
        device.setCode("radar-01");

        Metric defo = metric(301L, point.getId(), "defo_mm", "mm");
        Metric rate = metric(302L, point.getId(), "rate_mm_d", "mm/d");

        when(pointMapper.selectList(any())).thenReturn(List.of(point));
        when(deviceMapper.selectList(any())).thenReturn(List.of(device));
        when(metricMapper.selectList(any())).thenReturn(List.of(defo, rate));
        when(measurementMapper.selectList(any())).thenReturn(List.of());
        // insert 返回值未参与接入结果计算，使用 Mockito 的默认返回值即可。
        // 不为它设置桩，可同时避开 MyBatis-Plus 单行/批量 insert 的重载歧义。
    }

    @Test
    void backfillStoresRowsWithoutChangingCurrentState() {
        IngestResult result = service.ingest(request(IngestMode.BACKFILL));

        assertEquals("BACKFILL", result.getIngestMode());
        assertEquals(2, result.getAccepted());
        ArgumentCaptor<Measurement> rows = ArgumentCaptor.forClass(Measurement.class);
        verify(measurementMapper, times(2)).insert(rows.capture());
        assertEquals(List.of("BACKFILL", "BACKFILL"),
                rows.getAllValues().stream().map(Measurement::getIngestMode).toList());
        verify(broadcaster, never()).broadcastScoped(any(), any(), any());
        verify(alarmEngine, never()).evaluate(any(), any(), any(), any());
        verify(deviceMapper, never()).updateById(any(Device.class));
    }

    @Test
    void realtimeStillPublishesEvaluatesAndUpdatesHeartbeat() {
        IngestResult result = service.ingest(request(IngestMode.REALTIME));

        assertEquals("REALTIME", result.getIngestMode());
        assertEquals(2, result.getAccepted());
        verify(broadcaster, times(1)).broadcastScoped(any(), any(), any());
        verify(alarmEngine, times(2)).evaluate(any(), any(), any(), any());
        verify(deviceMapper, times(1)).updateById(device);
    }

    /**
     * 08①：目标失联的消息照常落库、照常推送，但**不进告警评估**。
     *
     * <p>关键在 {@code quality} 这里刻意写 "VALID"：{@code IngestService} 让设备显式声明的
     * quality 覆盖推导值，所以「失联就该标成可疑」那条路挡不住这条消息——闸只能设在评估入口。
     * 没有这道闸时，一条「目标失联 + 带旧值且低于阈值」的消息会**解除**已有的超限警情。</p>
     */
    @Test
    void disappearedMessageIsStoredAndBroadcastButNeverEvaluated() {
        IngestResult result = service.ingest(request(IngestMode.REALTIME, "disappeared"));

        assertEquals(2, result.getAccepted(), "失联消息仍要落库：行里的 state 属性是展示要用的");
        ArgumentCaptor<Measurement> rows = ArgumentCaptor.forClass(Measurement.class);
        verify(measurementMapper, times(2)).insert(rows.capture());
        assertEquals(List.of("VALID", "VALID"),
                rows.getAllValues().stream().map(Measurement::getQuality).toList(),
                "不改 quality：标成 SUSPECT 会连带触发语义错误的「数据质量异常」告警");
        verify(broadcaster, times(1)).broadcastScoped(any(), any(), any());
        verify(deviceMapper, times(1)).updateById(device);
        verify(alarmEngine, never()).evaluate(any(), any(), any(), any());
    }

    /** 大小写不敏感：设备侧大小写不受控，闸不能只认小写那一种写法。 */
    @Test
    void disappearedGateIsCaseInsensitive() {
        service.ingest(request(IngestMode.REALTIME, "DISAPPEARED"));

        verify(alarmEngine, never()).evaluate(any(), any(), any(), any());
    }

    /**
     * 对照：{@code state} 字段存在但值为 normal 时**照常**评估。
     * 没有这一条，上面两条断言就分不清「因为失联」和「因为消息里带了 state」。
     */
    @Test
    void normalStateStillEvaluates() {
        IngestResult result = service.ingest(request(IngestMode.REALTIME, "normal"));

        assertEquals(2, result.getAccepted());
        verify(alarmEngine, times(2)).evaluate(any(), any(), any(), any());
    }

    private IngestRequest request(IngestMode mode) {
        return request(mode, null);
    }

    private IngestRequest request(IngestMode mode, String state) {
        IngestMessage message = new IngestMessage();
        message.setSchemaVersion("1.0");
        message.setMessageId("msg-01");
        message.setDeviceId("radar-01");
        message.setPointCode("P-01");
        message.setCollectTime("2026-09-16T10:00:00+08:00");
        message.setSequence(1L);
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("defo_mm", 8.5);
        metrics.put("rate_mm_d", -3.2);
        message.setMetrics(metrics);
        message.setQuality("VALID");
        message.setState(state);

        IngestRequest request = new IngestRequest();
        request.setIngestMode(mode);
        request.setItems(List.of(message));
        return request;
    }

    private Metric metric(Long id, Long pointId, String code, String unit) {
        Metric metric = new Metric();
        metric.setId(id);
        metric.setPointId(pointId);
        metric.setCode(code);
        metric.setUnit(unit);
        return metric;
    }
}
