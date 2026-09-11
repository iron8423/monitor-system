package com.monitor.telemetry.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.project.entity.Metric;
import com.monitor.project.entity.MonitorPoint;
import com.monitor.project.mapper.MetricMapper;
import com.monitor.project.mapper.MonitorPointMapper;
import com.monitor.telemetry.dto.IngestMessage;
import com.monitor.telemetry.dto.IngestRequest;
import com.monitor.telemetry.dto.IngestResult;
import com.monitor.telemetry.entity.Measurement;
import com.monitor.telemetry.mapper.MeasurementMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * B1 接入（对齐 A 的 measurement 表）：
 *  - 幂等：device_id + message_id（表唯一约束）；
 *  - pointCode -> point_id、metricCode -> metric_id（用 A 的 MonitorPoint/Metric 查询）；
 *  - 一条含 N 测项 -> 拆 N 行，共用 message_id；position/signal/state -> attributes；
 *  - quality 缺省按 信号/state/metrics 推导。
 */
@Service
public class IngestService {

    private final MeasurementMapper mapper;
    private final MonitorPointMapper pointMapper;
    private final MetricMapper metricMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IngestService(MeasurementMapper mapper, MonitorPointMapper pointMapper, MetricMapper metricMapper) {
        this.mapper = mapper;
        this.pointMapper = pointMapper;
        this.metricMapper = metricMapper;
    }

    @Transactional
    public IngestResult ingest(IngestRequest req) {
        IngestResult res = new IngestResult();
        if (req == null || req.getItems() == null) return res;

        for (IngestMessage m : req.getItems()) {
            if (m == null) continue;
            if (!valid(m)) {
                res.setRejected(res.getRejected() + 1);
                res.getResults().add(item(m, "REJECTED", null));
                continue;
            }
            // 幂等：device_id + message_id 已存在 -> 整条去重
            if (mapper.countByMessageId(m.getDeviceId(), m.getMessageId()) > 0) {
                res.setDuplicates(res.getDuplicates() + 1);
                res.getResults().add(item(m, "DUPLICATE", null));
                continue;
            }
            // pointCode -> point_id（不存在则拒绝）
            MonitorPoint point = pointMapper.selectOne(
                    new LambdaQueryWrapper<MonitorPoint>().eq(MonitorPoint::getCode, m.getPointCode()));
            if (point == null) {
                res.setRejected(res.getRejected() + 1);
                res.getResults().add(item(m, "REJECTED", null));
                continue;
            }

            String q = (m.getQuality() != null && !m.getQuality().isEmpty())
                    ? m.getQuality() : deriveQuality(m);
            int rows = 0;
            if (m.getMetrics() != null) {
                int idx = 0;
                for (Map.Entry<String, Double> e : m.getMetrics().entrySet()) {
                    if (e.getValue() == null) continue;
                    // metricCode -> metric_id（须存在且属于该测点）
                    Metric metric = metricMapper.selectOne(new LambdaQueryWrapper<Metric>()
                            .eq(Metric::getPointId, point.getId())
                            .eq(Metric::getCode, e.getKey()));
                    if (metric == null) continue;
                    mapper.insert(buildRow(m, point, metric, e.getValue(), q, idx));
                    rows++;
                    idx++;
                }
            }
            if (rows == 0) {
                res.setRejected(res.getRejected() + 1);
                res.getResults().add(item(m, "REJECTED", q));
                continue;
            }
            res.setAccepted(res.getAccepted() + rows);
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

    private Measurement buildRow(IngestMessage m, MonitorPoint point, Metric metric,
                                 Double value, String quality, int idx) {
        Measurement row = new Measurement();
        row.setPointId(point.getId());
        row.setMetricId(metric.getId());
        row.setMetricCode(metric.getCode());
        row.setDeviceId(m.getDeviceId());
        row.setMessageId(m.getMessageId());
        row.setSeq(m.getSequence() != null ? m.getSequence() : (long) (idx + 1));
        row.setSchemaVersion(m.getSchemaVersion() != null ? m.getSchemaVersion() : "1.0");
        row.setMeasureValue(BigDecimal.valueOf(value));
        row.setUnit(metric.getUnit());
        row.setQuality(quality);
        row.setAttributes(toAttributes(m));
        row.setRawRef(m.getMessageId());
        row.setCollectTime(parseTime(m.getCollectTime()));
        row.setReceiveTime(parseTime(m.getReceiveTime()));
        row.setCreatedAt(LocalDateTime.now());
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

    private LocalDateTime parseTime(String s) {
        if (notBlank(s)) {
            try { return OffsetDateTime.parse(s).toLocalDateTime(); } catch (Exception ignored) {}
        }
        return LocalDateTime.now();
    }

    private boolean notBlank(String s) { return s != null && !s.trim().isEmpty(); }
}
