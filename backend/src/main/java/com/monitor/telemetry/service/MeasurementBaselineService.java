package com.monitor.telemetry.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.monitor.common.exception.BizException;
import com.monitor.common.util.Times;
import com.monitor.project.entity.MonitorPoint;
import com.monitor.project.mapper.MonitorPointMapper;
import com.monitor.scope.service.DataScopeService;
import com.monitor.telemetry.dto.MeasurementBaselineRequest;
import com.monitor.telemetry.dto.MeasurementBaselineVO;
import com.monitor.telemetry.entity.Measurement;
import com.monitor.telemetry.entity.MeasurementBaseline;
import com.monitor.telemetry.mapper.MeasurementBaselineMapper;
import com.monitor.telemetry.mapper.MeasurementMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 测量基准版本（复查清单 P1-4）。
 *
 * <p><b>为什么要有它</b>：雷达上报的 {@code defo_mm} 是"相对某次基准的累计形变"。
 * 换反射器、重新安装设备、设备自己归零之后，这个累计值从 0 重新开始，而平台上看到的
 * 只是一条继续延伸的曲线——两段不同基准的数据被接在同一条线上。只看着曲线的人
 * 分不出"稳定了"和"换了基准"，而这两者的处置完全相反。</p>
 *
 * <p><b>它不改写历史</b>：只记录"什么时候、为什么、谁、当时读数多少"。曲线怎么呈现
 * （画标记线、还是按当前基准折算）是读侧的事——改写历史会把"当时看到的就是这个样子"
 * 这条审计事实抹掉。</p>
 *
 * <p><b>原因用白名单短码</b>：自由文本会让"为什么"漂成每人一种写法，统计与筛选都做不了。
 * 短码与中文标签都在这里，前端直接用标签、接口调用方按短码判断。</p>
 */
@Service
@RequiredArgsConstructor
public class MeasurementBaselineService {

    /** 原因短码 → 中文标签。新增取值要同时加到这里与验收套件 20-baseline。 */
    private static final Map<String, String> REASONS = new LinkedHashMap<>();

    static {
        REASONS.put("REFLECTOR_REPLACED", "更换反射器");
        REASONS.put("DEVICE_REINSTALLED", "设备重新安装");
        REASONS.put("DEVICE_RELOCATED", "设备位移/转站");
        REASONS.put("POINT_RESTORED", "测点复位/修复");
        REASONS.put("DATA_RESET", "设备数据归零");
        REASONS.put("MANUAL", "人工确认（其它）");
    }

    /**
     * 允许的"未来"容差：与接入侧的前瞻容差同一个数（5 分钟）。
     * 现场设备与平台时钟差几秒是常态，卡死"必须 <= now"会让刚下现场的登记被拒。
     */
    private static final int FUTURE_TOLERANCE_SECONDS = 300;

    private final MeasurementBaselineMapper baselineMapper;
    private final MonitorPointMapper pointMapper;
    private final MeasurementMapper measurementMapper;
    private final DataScopeService dataScope;

    /** 原因白名单（给前端做下拉，也方便调用方对齐）。 */
    public static Map<String, String> reasons() {
        return new LinkedHashMap<>(REASONS);
    }

    /**
     * 登记一次基准变更。
     *
     * @param pointId  测点（必须存在且在调用者的数据范围内）
     * @param operator 登记人，取自登录身份（不采信请求体——与处置留痕同一口径）
     */
    public MeasurementBaselineVO create(Long pointId, MeasurementBaselineRequest req, String operator) {
        // 这一行是 404/403 的闸门，返回值本身不用——别改成"只查一次便于复用"，
        // 那会让"点不存在"与"点不可见"的判定被耦合到后面的业务分支里。
        requirePoint(pointId);
        if (req == null || req.getReason() == null || req.getReason().isBlank()) {
            throw new BizException("必须给出基准变更原因");
        }
        String reason = req.getReason().trim().toUpperCase();
        if (!REASONS.containsKey(reason)) {
            throw new BizException("基准变更原因不在白名单内: " + req.getReason()
                    + "（可选：" + String.join(" / ", REASONS.keySet()) + "）");
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime effectiveFrom = req.getEffectiveFrom() == null ? now : req.getEffectiveFrom();
        if (effectiveFrom.isAfter(now.plusSeconds(FUTURE_TOLERANCE_SECONDS))) {
            throw new BizException("基准生效时间不能晚于当前时间（允许 " + FUTURE_TOLERANCE_SECONDS
                    + " 秒内的时钟偏差）");
        }

        MeasurementBaseline entity = new MeasurementBaseline();
        entity.setPointId(pointId);
        entity.setEffectiveFrom(effectiveFrom);
        entity.setReason(reason);
        entity.setNote(req.getNote());
        // 换基准那一刻的读数：将来按当前基准折算时，它就是偏移基准。
        // 取"该时刻及之前的最新一行"，取不到（例如新点还没数据）就留空——
        // 空值表示"登记时没有可比对的读数"，比填 0 诚实得多（0 会被当成真实的零位）。
        Measurement last = measurementMapper.selectOne(
                measurementMapper.latestRowOf(pointId, "defo_mm", effectiveFrom).last("LIMIT 1"));
        entity.setBaselineValueMm(last == null || last.getMeasureValue() == null
                ? null : BigDecimal.valueOf(last.getMeasureValue()));
        entity.setOperator(operator);
        try {
            baselineMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 同一测点同一时刻只能有一条基准（V24 的唯一约束）：重复登记多半是点错了按钮，
            // 而"后写覆盖先写"会静默丢掉一条留痕——所以这里明确拒绝。
            throw new BizException(400, "该测点在 " + Times.iso(effectiveFrom) + " 已经登记过基准变更");
        }
        return toVO(baselineMapper.selectById(entity.getId()));
    }

    /** 当前生效的基准（{@code effective_from <= now} 里最新的一条）；从未登记过返回 {@code null}。 */
    public MeasurementBaselineVO current(Long pointId) {
        requirePoint(pointId);
        MeasurementBaseline row = baselineMapper.selectOne(new LambdaQueryWrapper<MeasurementBaseline>()
                .eq(MeasurementBaseline::getPointId, pointId)
                .le(MeasurementBaseline::getEffectiveFrom, LocalDateTime.now())
                .orderByDesc(MeasurementBaseline::getEffectiveFrom)
                .orderByDesc(MeasurementBaseline::getId)
                .last("LIMIT 1"));
        return row == null ? null : toVO(row);
    }

    /** 变更历史（新的在前）。 */
    public List<MeasurementBaselineVO> history(Long pointId) {
        requirePoint(pointId);
        return baselineMapper.selectList(new LambdaQueryWrapper<MeasurementBaseline>()
                        .eq(MeasurementBaseline::getPointId, pointId)
                        .orderByDesc(MeasurementBaseline::getEffectiveFrom)
                        .orderByDesc(MeasurementBaseline::getId))
                .stream().map(MeasurementBaselineService::toVO).toList();
    }

    /**
     * 窗口内的基准变更（给曲线画标记线用）。
     *
     * <p>不校验数据范围也不查测点：调用方是 {@code MeasurementQueryService.series}，
     * 它在入口处已经做过。这里再查一次是重复，而"多一层查询"在图上没有任何好处。</p>
     */
    public List<MeasurementBaselineVO> inWindow(Long pointId, LocalDateTime from, LocalDateTime to) {
        return baselineMapper.selectList(new LambdaQueryWrapper<MeasurementBaseline>()
                        .eq(MeasurementBaseline::getPointId, pointId)
                        .ge(MeasurementBaseline::getEffectiveFrom, from)
                        .le(MeasurementBaseline::getEffectiveFrom, to)
                        .orderByAsc(MeasurementBaseline::getEffectiveFrom))
                .stream().map(MeasurementBaselineService::toVO).toList();
    }

    /**
     * 批量：窗口内各测点的基准变更（P2-4 批量 series 用）。
     *
     * <p>与 {@link #inWindow} 同样是**一条**查询：批量端点的意义就是把 N 次请求压成一次，
     * 如果基准还逐点查，最贵的那部分没省下来。不校验数据范围，理由同 {@code inWindow}——
     * 调用方已经在入口处判过可见性。</p>
     */
    public Map<Long, List<MeasurementBaselineVO>> inWindowForPoints(Collection<Long> pointIds,
                                                                     LocalDateTime from,
                                                                     LocalDateTime to) {
        if (pointIds == null || pointIds.isEmpty()) {
            return Map.of();
        }
        return baselineMapper.selectList(new LambdaQueryWrapper<MeasurementBaseline>()
                        .in(MeasurementBaseline::getPointId, pointIds)
                        .ge(MeasurementBaseline::getEffectiveFrom, from)
                        .le(MeasurementBaseline::getEffectiveFrom, to)
                        .orderByAsc(MeasurementBaseline::getPointId)
                        .orderByAsc(MeasurementBaseline::getEffectiveFrom))
                .stream().collect(Collectors.groupingBy(MeasurementBaseline::getPointId,
                        Collectors.mapping(MeasurementBaselineService::toVO, Collectors.toList())));
    }

    /** 测点存在且可见（404 / 403 的口径与其它测点端点一致）。 */
    private MonitorPoint requirePoint(Long pointId) {
        MonitorPoint point = pointId == null ? null : pointMapper.selectById(pointId);
        if (point == null) {
            throw new BizException(404, "测点不存在: " + pointId);
        }
        dataScope.assertPointVisible(pointId);
        return point;
    }

    private static MeasurementBaselineVO toVO(MeasurementBaseline r) {
        MeasurementBaselineVO vo = new MeasurementBaselineVO();
        vo.setId(r.getId());
        vo.setPointId(r.getPointId());
        vo.setEffectiveFrom(Times.iso(r.getEffectiveFrom()));
        vo.setReason(r.getReason());
        vo.setReasonLabel(REASONS.getOrDefault(r.getReason(), r.getReason()));
        vo.setNote(r.getNote());
        vo.setBaselineValueMm(r.getBaselineValueMm());
        vo.setOperator(r.getOperator());
        vo.setCreatedAt(Times.iso(r.getCreatedAt()));
        return vo;
    }
}
