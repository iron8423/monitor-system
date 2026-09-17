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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 清单第 11 条：**并发**重复上报的杀伤半径只能有它自己那么大。
 *
 * <p>与 {@link IngestServiceModeTest} 分开，理由和 {@code AlarmConcurrencySemanticsTest} 一样：
 * 那一类测的是「什么消息该不该评估告警」，这一类测的是「撞了唯一键之后怎么收场」，
 * 断言的对象完全不同。两者共用同一套 mock 搭建，重复的那点样板比混在一起的代价小。</p>
 *
 * <p>为什么要有单测、而不是只靠 {@code tools/acceptance/12-ingest-concurrency.sh}：
 * 那个套件必须真造并发才有判别力，而并发是否真重叠取决于机器负载与请求时序（它的文件头写了
 * 这个假绿形态）。这里的单测用 mock 把「insert 抛 DuplicateKeyException」直接摆出来，
 * **不依赖时序**，跑一万遍都是同一个结果。两者互补：套件验真实并发下不变式成立，
 * 这里验每条分支各自的收场方式。</p>
 */
@ExtendWith(MockitoExtension.class)
class IngestServiceDuplicateTest {

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
        // 同 IngestServiceModeTest：纯单测没有 MyBatis-Plus 启动流程，
        // 不预热的话 ingest() 构造 LambdaQueryWrapper 时会抛 "can not find lambda cache"。
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

        when(pointMapper.selectList(any())).thenReturn(List.of(point));
        when(deviceMapper.selectList(any())).thenReturn(List.of(device));
        when(metricMapper.selectList(any())).thenReturn(List.of(
                metric(301L, "defo_mm"), metric(302L, "rate_mm_d")));
        // 预取：库里没有任何同 messageId 的行。这正是「并发重发」的前提——
        // 两个请求各自预取时对方都还没提交，于是都通过了预取，最后由唯一索引裁决。
        when(measurementMapper.selectList(any())).thenReturn(List.of());
    }

    /**
     * 核心不变式：**一条消息撞了唯一键，不该拖垮同批其它完全合法的消息。**
     *
     * <p>改动前这里是 500 + 整批回滚：{@code ingest} 整批一个事务，PG 在任何语句报错后把
     * 整个事务置为 aborted，于是后面那条跟重复毫无关系的消息也一起丢。客户端拿到的是
     * 5xx，无法据此判断「是重发还是真失败」——而这两种情况的正确处置完全相反。</p>
     */
    @Test
    void conflictingMessageIsDuplicateWhileBatchMatesSurvive() {
        when(measurementMapper.insert(any(Measurement.class))).thenAnswer(inv -> {
            Measurement row = inv.getArgument(0);
            if ("msg-dup".equals(row.getMessageId())) {
                throw new DuplicateKeyException("duplicate key value violates unique constraint \"uk_measurement_device_msg\"");
            }
            return 1;
        });
        // 回查：撞键那条确实已经在库里了（另一个请求先提交的）。
        when(measurementMapper.countByMessageId(anyString(), anyString())).thenReturn(1L);

        IngestResult result = service.ingest(request(message("msg-dup", 1), message("msg-ok", 2)));

        assertEquals(1, result.getDuplicates(), "撞键那条判重复");
        assertEquals(2, result.getAccepted(), "同批另一条的两个测项要照常落库");
        assertEquals(0, result.getRejected(), "重复不是拒收：4xx/拒收是「这条报文不对」，重复是「已经收过了」");
        assertEquals(List.of("DUPLICATE", "OK"),
                result.getResults().stream().map(IngestResult.Item::getStatus).toList());
        // 副作用只该由**活下来那条**产生一次，撞键那条一次都不该产生。
        // 这里的数不是「随便写个 1」：`msg-ok` 有 2 个测项，`msg-dup` 有 1 个——
        // 所以 evaluate 若把撞键那条也算进去就是 3 次，2 次恰好证明它没被评估。
        // 推送按消息 1 次、心跳每台设备整批 1 次（同样：算上撞键那条会是 2 次）。
        // 这三条断言最初被我写成 never()，跑出来红了——红的是断言本身：
        // `msg-ok` 落库后照常推送/评估/推进心跳**本来就是对的**，
        // 也正说明「撞键之后同批那条确实被正常处理了」，而不是整批悄悄跳过。
        verify(broadcaster, times(1)).broadcastScoped(any(), any(), any());
        verify(alarmEngine, times(2)).evaluate(any(), any(), any(), any());
        verify(deviceMapper, times(1)).updateById(device);
    }

    /**
     * 撞键**但**回查发现库里没有 -> 不是重复，是失败。重试一次；再失败就原样抛出去让调用方拿到 500。
     *
     * <p>这条是上一条的反面，也是它敢把 {@code DuplicateKeyException} 说成 {@code DUPLICATE} 的前提：
     * 只有在「库里确实有」时才敢这么说。少了这道回查，一次连接中断/锁超时就会被静默改写成
     * 「重复」——**那等于把一条真实测值悄悄丢掉，比报 500 更坏**：客户端看到 DUPLICATE 就不会重发。</p>
     */
    @Test
    void conflictWithoutStoredRowIsRetriedOnceThenRethrown() {
        when(measurementMapper.insert(any(Measurement.class)))
                .thenThrow(new DuplicateKeyException("transient"));
        when(measurementMapper.countByMessageId(anyString(), anyString())).thenReturn(0L);

        assertThrows(DuplicateKeyException.class,
                () -> service.ingest(request(message("msg-x", 1))),
                "库中没有这条 -> 不许说成重复，必须让调用方知道这次没落库");

        // 首次 + 重试一次。受控重试是为了吃掉「对方事务尚未提交」这种瞬时冲突；
        // 无限重试会把一个持续性的故障变成挂死的接入线程。
        verify(measurementMapper, times(2)).insert(any(Measurement.class));
    }

    /** 消息：指定 messageId 与前 n 个测项（值为 null 的测项会被跳过，这里都填实数）。 */
    private IngestMessage message(String messageId, int metricCount) {
        IngestMessage message = new IngestMessage();
        message.setSchemaVersion("1.0");
        message.setMessageId(messageId);
        message.setDeviceId("radar-01");
        message.setPointCode("P-01");
        message.setCollectTime("2026-09-17T10:00:00+08:00");
        message.setSequence(1L);
        Map<String, Double> metrics = new LinkedHashMap<>();
        if (metricCount >= 1) metrics.put("defo_mm", 8.5);
        if (metricCount >= 2) metrics.put("rate_mm_d", -3.2);
        message.setMetrics(metrics);
        message.setQuality("VALID");
        return message;
    }

    private IngestRequest request(IngestMessage... messages) {
        IngestRequest request = new IngestRequest();
        request.setIngestMode(IngestMode.REALTIME);
        request.setItems(List.of(messages));
        return request;
    }

    private Metric metric(Long id, String code) {
        Metric metric = new Metric();
        metric.setId(id);
        metric.setPointId(101L);
        metric.setCode(code);
        metric.setUnit("mm");
        return metric;
    }
}
