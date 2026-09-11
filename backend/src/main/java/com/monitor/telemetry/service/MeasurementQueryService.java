package com.monitor.telemetry.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.common.exception.BizException;
import com.monitor.common.util.Times;
import com.monitor.project.entity.Metric;
import com.monitor.project.entity.MonitorPoint;
import com.monitor.project.mapper.MetricMapper;
import com.monitor.project.mapper.MonitorPointMapper;
import com.monitor.telemetry.dto.PointLatestVO;
import com.monitor.telemetry.dto.PointSeriesVO;
import com.monitor.telemetry.entity.Measurement;
import com.monitor.telemetry.mapper.MeasurementMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * M1：测点数据查询（latest / series）。口径见 docs/message-contract.md 与《B侧接口契约_M0》§3：
 * id 用数值、code 用字符串、时间 ISO8601 带时区、位移 mm / 速率 mm/d。
 */
@Service
@SuppressWarnings("null")
public class MeasurementQueryService {

    private static final String DEFAULT_METRIC = "defo_mm";

    private final MeasurementMapper mapper;
    private final MonitorPointMapper pointMapper;
    private final MetricMapper metricMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MeasurementQueryService(MeasurementMapper mapper,
                                   MonitorPointMapper pointMapper,
                                   MetricMapper metricMapper) {
        this.mapper = mapper;
        this.pointMapper = pointMapper;
        this.metricMapper = metricMapper;
    }

    /**
     * 测点最新值：取该点 collect_time 最大的一行，再拉同 message_id 的兄弟行凑齐各测项。
     *
     * <p>{@code collect_time} 相同时必须再有确定的次序：同一测点在**同一次采集时刻**出现两行
     * （设备重传换了 messageId、或两台设备覆盖同一点）时，只按 collect_time 排序的话取到哪一行
     * 由数据库返回顺序决定，「最新值」不可复现，兄弟测项也会跟着那一行的 messageId 走。
     * 用 {@code id} 兜底：主键单调递增，等价于「后写库的那条覆盖先写的」，
     * 且不像 {@code receive_time} 那样有 NULL 排序的方言差异（PG 的 DESC 把 NULL 排在最前、H2 排在最后）。</p>
     */
    public PointLatestVO latest(Long pointId) {
        MonitorPoint p = requirePoint(pointId);
        PointLatestVO vo = new PointLatestVO();
        vo.setPointId(p.getId());
        vo.setPointCode(p.getCode());

        Measurement last = mapper.selectOne(new LambdaQueryWrapper<Measurement>()
                .eq(Measurement::getPointId, pointId)
                .isNotNull(Measurement::getCollectTime)
                .orderByDesc(Measurement::getCollectTime)
                .orderByDesc(Measurement::getId)
                .last("LIMIT 1"));
        if (last == null) {
            return vo;   // 测点存在但暂无数据 -> latest / state 为 null
        }

        // 一条消息拆 N 行（D2），同 message_id 的兄弟行才是同一时刻的完整测项集合
        List<Measurement> rows = mapper.selectList(new LambdaQueryWrapper<Measurement>()
                .eq(Measurement::getPointId, pointId)
                .eq(Measurement::getMessageId, last.getMessageId())
                .eq(last.getDeviceId() != null, Measurement::getDeviceId, last.getDeviceId()));
        rows.sort(Comparator.comparing(Measurement::getMetricCode, Comparator.nullsLast(Comparator.naturalOrder())));

        Map<String, Object> att = parseAttributes(last.getAttributes());
        Map<String, Object> latest = new LinkedHashMap<>();
        latest.put("collectTime", Times.iso(last.getCollectTime()));
        for (Measurement r : rows) {
            latest.put(r.getMetricCode(), r.getMeasureValue());
        }
        latest.put("quality", last.getQuality());
        latest.put("signal", att.get("signal"));
        latest.put("position", att.get("position"));

        vo.setLatest(latest);
        vo.setState(att.get("state") == null ? null : String.valueOf(att.get("state")));
        return vo;
    }

    /**
     * 测点时序曲线。
     * @param granularity raw（默认，原样返回）/ hour / day（按桶取均值）
     */
    public PointSeriesVO series(Long pointId, String metricCode, String from, String to, String granularity) {
        MonitorPoint p = requirePoint(pointId);
        String code = blank(metricCode) ? DEFAULT_METRIC : metricCode;
        LocalDateTime f = Times.parse(from, "from");
        LocalDateTime t = Times.parse(to, "to");

        List<Measurement> rows = mapper.selectList(new LambdaQueryWrapper<Measurement>()
                .eq(Measurement::getPointId, pointId)
                .eq(Measurement::getMetricCode, code)
                .ge(f != null, Measurement::getCollectTime, f)
                .le(t != null, Measurement::getCollectTime, t)
                .orderByAsc(Measurement::getCollectTime));

        PointSeriesVO vo = new PointSeriesVO();
        vo.setPointId(p.getId());
        vo.setPointCode(p.getCode());
        vo.setMetricCode(code);
        vo.setUnit(unitOf(pointId, code));
        vo.setPoints(bucket(rows, granularity));
        return vo;
    }

    /** raw 原样输出；hour/day 按时间桶取均值。 */
    private List<PointSeriesVO.Item> bucket(List<Measurement> rows, String granularity) {
        String g = blank(granularity) ? "raw" : granularity.trim().toLowerCase();
        List<PointSeriesVO.Item> out = new ArrayList<>();
        if ("raw".equals(g)) {
            for (Measurement r : rows) {
                out.add(new PointSeriesVO.Item(Times.iso(r.getCollectTime()), r.getMeasureValue()));
            }
            return out;
        }
        if (!"hour".equals(g) && !"day".equals(g)) {
            throw new BizException("granularity 仅支持 raw / hour / day: " + granularity);
        }

        Map<LocalDateTime, List<Double>> grouped = new TreeMap<>();
        for (Measurement r : rows) {
            if (r.getCollectTime() == null || r.getMeasureValue() == null) {
                continue;
            }
            LocalDateTime key = "day".equals(g)
                    ? r.getCollectTime().truncatedTo(ChronoUnit.DAYS)
                    : r.getCollectTime().truncatedTo(ChronoUnit.HOURS);
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(r.getMeasureValue().doubleValue());
        }
        for (Map.Entry<LocalDateTime, List<Double>> e : grouped.entrySet()) {
            double avg = e.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(0d);
            out.add(new PointSeriesVO.Item(Times.iso(e.getKey()), round(avg)));
        }
        return out;
    }

    /** 单位以档案 metric 表为准（A 唯一维护），查不到时退回约定值。 */
    private String unitOf(Long pointId, String code) {
        Metric m = metricMapper.selectOne(new LambdaQueryWrapper<Metric>()
                .eq(Metric::getPointId, pointId)
                .eq(Metric::getCode, code)
                .last("LIMIT 1"));
        if (m != null && !blank(m.getUnit())) {
            return m.getUnit();
        }
        return "rate_mm_d".equals(code) ? "mm/d" : "mm";
    }

    private MonitorPoint requirePoint(Long pointId) {
        MonitorPoint p = pointId == null ? null : pointMapper.selectById(pointId);
        if (p == null) {
            throw new BizException(404, "测点不存在: " + pointId);
        }
        return p;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseAttributes(String json) {
        if (blank(json)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }


    private static Double round(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
