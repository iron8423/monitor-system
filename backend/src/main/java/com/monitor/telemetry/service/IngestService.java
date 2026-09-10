package com.monitor.telemetry.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.alarm.service.AlarmEngine;
import com.monitor.project.entity.MonitorPoint;
import com.monitor.project.mapper.MonitorPointMapper;
import com.monitor.telemetry.dto.IngestMessage;
import com.monitor.telemetry.dto.IngestRequest;
import com.monitor.telemetry.dto.IngestResult;
import com.monitor.telemetry.entity.Measurement;
import com.monitor.telemetry.mapper.MeasurementMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * B1：接入。规则（对齐 A 冻结口径）：
 *  - 幂等：device_id + message_id（重复整条去重）；
 *  - 落库：一条含 N 测项 -> 拆 N 行 measurement，共用 message_id；
 *  - 附加字段 position/signal/state -> attributes(JSON <=1024)；
 *  - 质量：quality 缺省按 信号/state/metrics 推导。
 */
@Service
public class IngestService {

    private final MeasurementMapper mapper;
    private final MonitorPointMapper pointMapper;
    private final AlarmEngine alarmEngine;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IngestService(MeasurementMapper mapper, MonitorPointMapper pointMapper, AlarmEngine alarmEngine) {
        this.mapper = mapper;
        this.pointMapper = pointMapper;
        this.alarmEngine = alarmEngine;
    }

    @Transactional
    public IngestResult ingest(IngestRequest req) {
        IngestResult res = new IngestResult();
        if (req == null || req.getItems() == null) {
            return res;
        }
        for (IngestMessage m : req.getItems()) {
            if (m == null) continue;
            if (!valid(m)) {
                res.setRejected(res.getRejected() + 1);
                res.getResults().add(item(m, "REJECTED", null));
                continue;
            }
            // 点号 -> 档案主键（point_id NOT NULL，落库前必须解析；未知点号整条拒绝）
            Long pointId = resolvePointId(m.getPointCode());
            if (pointId == null) {
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
                    mapper.insert(buildRow(m, pointId, e.getKey(), e.getValue(), q));
                    rows++;
                }
            }
            if (rows == 0) {  // 无有效测项 -> 视为无效
                res.setRejected(res.getRejected() + 1);
                res.getResults().add(item(m, "REJECTED", q));
                continue;
            }
            // 落库成功 -> 规则评估（M2：超限即生成警情）。引擎内部已隔离异常，不影响接入主流程
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

    private Measurement buildRow(IngestMessage m, Long pointId, String metricCode, Double value, String quality) {
        Measurement row = new Measurement();
        row.setMessageId(m.getMessageId());
        row.setDeviceId(m.getDeviceId());
        row.setPointId(pointId);
        row.setPointCode(m.getPointCode());
        row.setMetricCode(metricCode);
        row.setCollectTime(parseTime(m.getCollectTime()));
        row.setReceiveTime(parseTime(m.getReceiveTime()));
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

    private LocalDateTime parseTime(String s) {
        if (notBlank(s)) {
            try { return OffsetDateTime.parse(s).toLocalDateTime(); } catch (Exception ignored) {}
        }
        return LocalDateTime.now();
    }

    /** 点号 -> 档案主键。用档案点号（P-HK01…P-BP04）落 point_id，兼容 D10（id 数值 / code 字符串）。 */
    private Long resolvePointId(String pointCode) {
        MonitorPoint p = pointMapper.selectOne(new LambdaQueryWrapper<MonitorPoint>()
                .eq(MonitorPoint::getCode, pointCode)
                .last("LIMIT 1"));
        return p == null ? null : p.getId();
    }

    private boolean notBlank(String s) { return s != null && !s.trim().isEmpty(); }
}
