package com.monitor.telemetry.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.alarm.service.AlarmEngine;
import com.monitor.asset.entity.Device;
import com.monitor.asset.mapper.DeviceMapper;
import com.monitor.common.exception.BizException;
import com.monitor.common.sse.SseBroadcaster;
import com.monitor.common.util.Times;
import com.monitor.project.entity.MonitorPoint;
import com.monitor.project.mapper.MonitorPointMapper;
import com.monitor.telemetry.dto.IngestMessage;
import com.monitor.telemetry.dto.IngestRequest;
import com.monitor.scope.service.DataScopeService;
import com.monitor.telemetry.dto.IngestResult;
import com.monitor.telemetry.dto.MeasurementEvent;
import com.monitor.telemetry.entity.Measurement;
import com.monitor.telemetry.mapper.MeasurementMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * B1：接入。规则（对齐 A 冻结口径）：
 *  - 幂等：device_id + message_id（重复整条去重）；
 *  - 落库：一条含 N 测项 -> 拆 N 行 measurement，共用 message_id；
 *  - 附加字段 position/signal/state -> attributes(JSON <=1024)；
 *  - 质量：quality 缺省按 信号/state/metrics 推导；
 *  - 时间：collectTime 必填且必须可解析（非法 -> 整条 REJECTED）；receiveTime 缺省取当前时间。
 */
@Service
@SuppressWarnings("null")
public class IngestService {

    private final MeasurementMapper mapper;
    private final MonitorPointMapper pointMapper;
    private final DeviceMapper deviceMapper;
    private final AlarmEngine alarmEngine;
    private final SseBroadcaster broadcaster;
    private final DataScopeService dataScope;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IngestService(MeasurementMapper mapper, MonitorPointMapper pointMapper, DeviceMapper deviceMapper,
                         AlarmEngine alarmEngine, SseBroadcaster broadcaster, DataScopeService dataScope) {
        this.mapper = mapper;
        this.pointMapper = pointMapper;
        this.deviceMapper = deviceMapper;
        this.alarmEngine = alarmEngine;
        this.broadcaster = broadcaster;
        this.dataScope = dataScope;
    }

    @Transactional
    public IngestResult ingest(IngestRequest req) {
        IngestResult res = new IngestResult();
        if (req == null || req.getItems() == null) {
            return res;
        }
        for (IngestMessage m : req.getItems()) {
            if (m == null) continue;
            // collectTime 是设备侧时间，缺失/格式非法一律拒收（见 parseCollectTime 的说明）
            LocalDateTime collectAt = parseCollectTime(m.getCollectTime());
            if (!valid(m) || collectAt == null) {
                res.setRejected(res.getRejected() + 1);
                res.getResults().add(item(m, "REJECTED", null));
                continue;
            }
            // 点号/设备都必须在档案中存在（契约 §2：deviceId、pointCode 不存在 -> REJECTED）
            Long pointId = resolvePointId(m.getPointCode());
            Device device = resolveDevice(m.getDeviceId());
            if (pointId == null || device == null) {
                res.setRejected(res.getRejected() + 1);
                res.getResults().add(item(m, "REJECTED", null));
                continue;
            }
            // 幂等：device_id + message_id 已存在 -> 整条跳过
            if (mapper.countByMessageId(m.getDeviceId(), m.getMessageId()) > 0) {
                res.setDuplicates(res.getDuplicates() + 1);
                res.getResults().add(item(m, "DUPLICATE", null));
                continue;
            }
            String q = (m.getQuality() != null && !m.getQuality().isEmpty())
                    ? m.getQuality() : deriveQuality(m);
            int rows = 0;
            if (m.getMetrics() != null) {
                for (Map.Entry<String, Double> e : m.getMetrics().entrySet()) {
                    if (e.getValue() == null) continue;
                    mapper.insert(buildRow(m, pointId, e.getKey(), e.getValue(), q, collectAt));
                    rows++;
                }
            }
            if (rows == 0) {  // 无有效测项 -> 视为无效
                res.setRejected(res.getRejected() + 1);
                res.getResults().add(item(m, "REJECTED", q));
                continue;
            }
            // 实时推送（前端 3D 与曲线即时刷新）。必须排在告警评估**之前**：
            // 两者在事务中都是「提交后」回调，按注册顺序发出，先有测值再有警情才合乎因果。
            // 广播失败只摘连接，不影响接入结果
            broadcaster.broadcastScoped(SseBroadcaster.EVENT_MEASUREMENT,
                    MeasurementEvent.of(pointId, m.getPointCode(), Times.iso(collectAt), m.getMetrics(), q),
                    () -> dataScope.projectIdsOfPoint(pointId));

            // 回写设备最近上报时间：A 的在线判定（DeviceStatusPolicy）只认这个字段，
            // 不写则设备即使一直在报数也永远显示离线。取平台接收时间（无则当前时间）
            device.setLastReportTime(parseReceiveTime(m.getReceiveTime()));
            deviceMapper.updateById(device);

            // 规则评估（M2：超限即生成警情）。引擎内部已隔离异常，不影响接入主流程
            for (Map.Entry<String, Double> e : m.getMetrics().entrySet()) {
                if (e.getValue() != null) {
                    alarmEngine.evaluate(pointId, e.getKey(), e.getValue(), q);
                }
            }
            res.setAccepted(res.getAccepted() + rows);   // accepted = 落库行数
            res.getResults().add(item(m, "OK", q));
        }
        return res;
    }

    private boolean valid(IngestMessage m) {
        return notBlank(m.getDeviceId()) && notBlank(m.getMessageId()) && notBlank(m.getPointCode());
    }

    private String deriveQuality(IngestMessage m) {
        if (m.getMetrics() == null || m.getMetrics().isEmpty()) return "SUSPECT";
        if (m.getSignal() != null && m.getSignal() < 0.3) return "SUSPECT";
        if ("suspicious".equalsIgnoreCase(m.getState())) return "SUSPECT";
        for (Double v : m.getMetrics().values()) {
            if (v == null || v.isNaN() || v.isInfinite()) return "FAULT";
        }
        return "VALID";
    }

    private Measurement buildRow(IngestMessage m, Long pointId, String metricCode, Double value,
                                 String quality, LocalDateTime collectAt) {
        Measurement row = new Measurement();
        row.setMessageId(m.getMessageId());
        row.setDeviceId(m.getDeviceId());
        row.setPointId(pointId);
        row.setPointCode(m.getPointCode());
        row.setMetricCode(metricCode);
        row.setCollectTime(collectAt);
        row.setReceiveTime(parseReceiveTime(m.getReceiveTime()));
        row.setMeasureValue(value);
        row.setQuality(quality);
        row.setAttributes(toAttributes(m));
        row.setRawRef(m.getMessageId());
        return row;
    }

    private String toAttributes(IngestMessage m) {
        Map<String, Object> att = new HashMap<>();
        if (m.getPosition() != null) att.put("position", m.getPosition());
        if (m.getSignal() != null) att.put("signal", m.getSignal());
        if (m.getState() != null) att.put("state", m.getState());
        try {
            String s = objectMapper.writeValueAsString(att);
            return s.length() > 1024 ? s.substring(0, 1024) : s;
        } catch (Exception e) {
            return "{}";
        }
    }

    private IngestResult.Item item(IngestMessage m, String status, String quality) {
        IngestResult.Item it = new IngestResult.Item();
        it.setPointCode(m.getPointCode());
        it.setCollectTime(m.getCollectTime());
        it.setStatus(status);
        it.setQuality(quality);
        return it;
    }

    /**
     * 解析设备侧的 {@code collectTime}；**缺失或格式非法返回 {@code null}**，由调用方按整条
     * {@code REJECTED} 处理，不替设备编一个时间。
     *
     * <p>早期实现是「解析不了就取 {@code now()}」，那不只是数据不准，还会**掩盖设备离线**：
     * 设备发来坏时间戳，平台按「刚刚收到」盖章，而 {@link com.monitor.asset.DeviceStatusPolicy}
     * 只认 {@code last_report_time}——于是设备一直在报垃圾却永远显示在线，离线告警永远不会响。
     * 时间戳是设备的契约义务（message-contract §2），坏数据应当明确拒收、计入 {@code rejected}。</p>
     *
     * <p>带偏移的写法必须**换算**到平台时区（{@link Times#ZONE}），不能只取 {@code toLocalDateTime()}——
     * 那会丢掉偏移只留墙上时间：设备用 UTC 报 {@code 06:00Z} 会被存成 06:00 并当作 +08:00 回读，整错 8 小时。
     * 宽容度与查询侧一致，统一委托 {@link Times#parse}。</p>
     */
    private LocalDateTime parseCollectTime(String s) {
        try {
            return Times.parse(s, "collectTime");
        } catch (BizException e) {
            return null;
        }
    }

    /**
     * 解析平台侧的 {@code receiveTime}：没给就取当前时间——**这是对的**，
     * 它本就是「平台什么时候收到的」，兜底取 now() 不会凭空造出错误的时间语义。
     */
    private LocalDateTime parseReceiveTime(String s) {
        LocalDateTime t = parseCollectTime(s);
        return t == null ? LocalDateTime.now() : t;
    }

    /** 点号 -> 档案主键。用档案点号（P-HK01…P-BP04）落 point_id，兼容 D10（id 数值 / code 字符串）。 */
    private Long resolvePointId(String pointCode) {
        MonitorPoint p = pointMapper.selectOne(new LambdaQueryWrapper<MonitorPoint>()
                .eq(MonitorPoint::getCode, pointCode)
                .last("LIMIT 1"));
        return p == null ? null : p.getId();
    }

    /** 设备码 -> 设备档案（消息里的 deviceId 是设备 code，如 radar-001）。 */
    private Device resolveDevice(String deviceCode) {
        return deviceMapper.selectOne(new LambdaQueryWrapper<Device>()
                .eq(Device::getCode, deviceCode)
                .last("LIMIT 1"));
    }

    private boolean notBlank(String s) { return s != null && !s.trim().isEmpty(); }
}
