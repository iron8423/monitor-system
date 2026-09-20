package com.monitor.telemetry.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.common.exception.BizException;
import com.monitor.common.util.Times;
import com.monitor.project.entity.Metric;
import com.monitor.project.entity.MonitorPoint;
import com.monitor.project.mapper.MetricMapper;
import com.monitor.project.mapper.MonitorPointMapper;
import com.monitor.scope.service.DataScopeService;
import com.monitor.telemetry.dto.MeasurementBucket;
import com.monitor.telemetry.dto.PointLatestVO;
import com.monitor.telemetry.dto.PointSeriesVO;
import com.monitor.telemetry.entity.Measurement;
import com.monitor.telemetry.mapper.MeasurementMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * M1：测点数据查询（latest / series）。口径见 docs/message-contract.md 与《B侧接口契约_M0》§3：
 * id 用数值、code 用字符串、时间 ISO8601 带时区、位移 mm / 速率 mm/d。
 */
@Service
public class MeasurementQueryService {

    private static final String DEFAULT_METRIC = "defo_mm";

    private final MeasurementMapper mapper;
    private final MonitorPointMapper pointMapper;
    private final MetricMapper metricMapper;
    private final DataScopeService dataScope;
    /** 窗口内的基准变更加进 series 响应（P1-4）：曲线要能标出"这里换过基准" */
    private final MeasurementBaselineService baselineService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MeasurementQueryService(MeasurementMapper mapper,
                                   MonitorPointMapper pointMapper,
                                   MetricMapper metricMapper,
                                   DataScopeService dataScope,
                                   MeasurementBaselineService baselineService) {
        this.mapper = mapper;
        this.pointMapper = pointMapper;
        this.metricMapper = metricMapper;
        this.dataScope = dataScope;
        this.baselineService = baselineService;
    }

    /**
     * 测点最新值：取该点 collect_time 最大的一行，再拉同 message_id 的兄弟行凑齐各测项。
     *
     * <p>「最新一行」的判据由 {@link MeasurementMapper#latestRowOf} 单点定义
     * （{@code collect_time DESC, id DESC}）——{@link ProjectSummaryService} 取最新形变时走同一个方法。
     * 此前两处各写各的排序，一处带 id 兜底一处不带，同刻两行时同一测点会给出两个值。</p>
     *
     * <p>上界取 {@code now()}：未来采集时间的行不能成为「当前值」（清单第 10 条），
     * 理由与取值见 {@link MeasurementMapper#latestRowOf}。</p>
     */
    public PointLatestVO latest(Long pointId) {
        MonitorPoint p = requirePoint(pointId);
        Measurement last = mapper.selectOne(
                mapper.latestRowOf(pointId, null, LocalDateTime.now()).last("LIMIT 1"));
        if (last == null) {
            return emptyLatest(p);   // 测点存在但暂无数据 -> latest / state 为 null
        }

        // 一条消息拆 N 行（D2），同 message_id 的兄弟行才是同一时刻的完整测项集合
        List<Measurement> rows = mapper.selectList(new LambdaQueryWrapper<Measurement>()
                .eq(Measurement::getPointId, pointId)
                .eq(Measurement::getMessageId, last.getMessageId())
                .eq(last.getDeviceId() != null, Measurement::getDeviceId, last.getDeviceId()));
        rows.sort(Comparator.comparing(Measurement::getMetricCode, Comparator.nullsLast(Comparator.naturalOrder())));

        return latestVO(p, last, rows);
    }

    /**
     * 项目级批量最新值：1000 个测点只发一个 HTTP 请求、执行两条批量 SQL。
     * 单点端点保留给详情页和兼容客户端使用。
     */
    public List<PointLatestVO> latestOfProject(Long projectId) {
        dataScope.assertProjectVisible(projectId);
        List<MonitorPoint> points = pointMapper.selectByProjectId(projectId);
        if (points.isEmpty()) {
            return List.of();
        }
        List<Long> pointIds = points.stream().map(MonitorPoint::getId).toList();
        // 上界取 now()：这是大屏那条路径（一次 1000 点），未来时间的行一旦成为「当前值」，
        // 界面会一直显示那个值直到有人刷新缓存——查询侧的兜底在这三个调用点里价值最高。
        List<Measurement> lastRows = mapper.latestRowsOfPoints(pointIds, LocalDateTime.now());
        Map<Long, Measurement> lastByPoint = new HashMap<>();
        Set<String> messageIds = new HashSet<>();
        for (Measurement row : lastRows) {
            lastByPoint.put(row.getPointId(), row);
            if (row.getMessageId() != null) messageIds.add(row.getMessageId());
        }

        Map<String, List<Measurement>> siblings = new HashMap<>();
        if (!messageIds.isEmpty()) {
            for (Measurement row : mapper.rowsByMessageIds(new ArrayList<>(messageIds))) {
                siblings.computeIfAbsent(messageKey(row), ignored -> new ArrayList<>()).add(row);
            }
        }

        List<PointLatestVO> result = new ArrayList<>(points.size());
        for (MonitorPoint point : points) {
            Measurement last = lastByPoint.get(point.getId());
            if (last == null) {
                result.add(emptyLatest(point));
                continue;
            }
            List<Measurement> rows = new ArrayList<>(
                    siblings.getOrDefault(messageKey(last), List.of(last)));
            rows.sort(Comparator.comparing(Measurement::getMetricCode,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            result.add(latestVO(point, last, rows));
        }
        return result;
    }

    private PointLatestVO latestVO(MonitorPoint p, Measurement last, List<Measurement> rows) {
        PointLatestVO vo = emptyLatest(p);

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

    private static PointLatestVO emptyLatest(MonitorPoint p) {
        PointLatestVO vo = new PointLatestVO();
        vo.setPointId(p.getId());
        vo.setPointCode(p.getCode());
        return vo;
    }

    private static String messageKey(Measurement row) {
        return row.getPointId() + "|" + row.getDeviceId() + "|" + row.getMessageId();
    }

    /**
     * 测点时序曲线。
     *
     * <p>时间窗与规模上限见 {@link SeriesWindowPolicy}（P0-3）：{@code from}/{@code to}
     * 均可省略，省略时**补默认窗口**（近 24 小时）而不是"不限时间"；跨度上限 31 天；
     * {@code raw} 单次最多 {@value SeriesWindowPolicy#MAX_RAW_POINTS} 点，超过返回 400
     * 并提示改用分桶——不静默截断，截断过的曲线看起来和真的一样。</p>
     *
     * @param granularity raw（默认，原样返回）/ hour / day（按桶取均值，聚合在 SQL 里完成）
     */
    public PointSeriesVO series(Long pointId, String metricCode, String from, String to, String granularity) {
        MonitorPoint p = requirePoint(pointId);
        String code = blank(metricCode) ? DEFAULT_METRIC : metricCode;
        // 粒度先校验再查库：非法值不该先付一次全窗口扫描的代价
        String g = granularityOf(granularity);
        SeriesWindowPolicy.Window window = SeriesWindowPolicy.resolve(
                Times.parse(from, "from"), Times.parse(to, "to"), LocalDateTime.now());

        PointSeriesVO vo = new PointSeriesVO();
        vo.setPointId(p.getId());
        vo.setPointCode(p.getCode());
        vo.setMetricCode(code);
        vo.setUnit(unitOf(pointId, code));
        vo.setFrom(Times.iso(window.from()));
        vo.setTo(Times.iso(window.to()));
        vo.setWindowDefaulted(window.fromDefaulted() && window.toDefaulted());
        vo.setBaselines(baselineService.inWindow(pointId, window.from(), window.to()));
        vo.setPoints("raw".equals(g)
                ? rawPoints(pointId, code, window)
                : bucketedPoints(pointId, code, window, g));
        return vo;
    }

    /** {@code granularity} 白名单与归一化。 */
    private static String granularityOf(String granularity) {
        String g = blank(granularity) ? "raw" : granularity.trim().toLowerCase();
        if (!"raw".equals(g) && !"hour".equals(g) && !"day".equals(g)) {
            throw new BizException("granularity 仅支持 raw / hour / day: " + granularity);
        }
        return g;
    }

    /**
     * raw：逐行返回，用 {@code LIMIT 上限+1} 探测超限。
     *
     * <p>多取那一行的意义：拿满上限**恰好**等于 5000 行是合法请求，只有第 5001 行存在时
     * 才说明被截断了。少了这个 +1，就只能靠"结果数 == 上限"去猜，而那种猜法会把
     * 合法的边界请求误判成超限。</p>
     */
    private List<PointSeriesVO.Item> rawPoints(Long pointId, String code, SeriesWindowPolicy.Window w) {
        List<Measurement> rows = mapper.selectList(new LambdaQueryWrapper<Measurement>()
                .eq(Measurement::getPointId, pointId)
                .eq(Measurement::getMetricCode, code)
                .ge(Measurement::getCollectTime, w.from())
                .le(Measurement::getCollectTime, w.to())
                .orderByAsc(Measurement::getCollectTime)
                .last("LIMIT " + (SeriesWindowPolicy.MAX_RAW_POINTS + 1)));
        if (rows.size() > SeriesWindowPolicy.MAX_RAW_POINTS) {
            throw new BizException("窗口内原始点数超过 " + SeriesWindowPolicy.MAX_RAW_POINTS
                    + " 条：请缩小时间范围，或改用 granularity=hour / day 查看趋势");
        }
        List<PointSeriesVO.Item> out = new ArrayList<>();
        for (Measurement r : rows) {
            out.add(new PointSeriesVO.Item(Times.iso(r.getCollectTime()), r.getMeasureValue()));
        }
        return out;
    }

    /**
     * hour/day：分桶与求均值都在 SQL 里做（{@link MeasurementMapper#averageByBucket}），
     * 返回的行数等于桶数而不是原始行数。
     */
    private List<PointSeriesVO.Item> bucketedPoints(Long pointId, String code,
                                                    SeriesWindowPolicy.Window w, String g) {
        List<PointSeriesVO.Item> out = new ArrayList<>();
        for (MeasurementBucket b : mapper.averageByBucket(pointId, code, w.from(), w.to(),
                "day".equals(g))) {
            if (b.getBucketTime() == null || b.getBucketValue() == null) {
                continue;   // collect_time 为空的行会落进 NULL 桶，跳过（与改造前的行为一致）
            }
            out.add(new PointSeriesVO.Item(Times.iso(b.getBucketTime()),
                    round(b.getBucketValue().doubleValue())));
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

    /**
     * 取测点档案，不存在 404、不在数据范围内 403。
     *
     * <p>范围断言放在这里而不是两个公开方法里：{@code latest} 与 {@code series}
     * 是仅有的两个入口，都经由本方法——加在链路的必经点上，将来多一个查询端点也不会漏。</p>
     */
    private MonitorPoint requirePoint(Long pointId) {
        MonitorPoint p = pointId == null ? null : pointMapper.selectById(pointId);
        if (p == null) {
            throw new BizException(404, "测点不存在: " + pointId);
        }
        dataScope.assertPointVisible(pointId);
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
