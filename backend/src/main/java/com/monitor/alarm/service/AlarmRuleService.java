package com.monitor.alarm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.monitor.alarm.dto.AlarmRuleRequest;
import com.monitor.alarm.dto.AlarmRuleVO;
import com.monitor.alarm.entity.AlarmRule;
import com.monitor.alarm.mapper.AlarmRuleMapper;
import com.monitor.common.exception.BizException;
import com.monitor.project.entity.MonitorPoint;
import com.monitor.project.mapper.MonitorPointMapper;
import com.monitor.scope.service.DataScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 告警规则 CRUD（《B侧接口契约_M0》§4）。对外用 type / value / level，库内为 rule_type / threshold_value / alarm_level。
 *
 * <p>{@code type} 目前只接受 {@code THRESHOLD}：速率类告警可用雷达已上报的 {@code rate_mm_d}
 * 测项配 {@code THRESHOLD} 规则覆盖，无需引擎另算窗口速率（两个口径会打架）。
 * {@code CHANGE}（窗口内变化量）无测项可替代，待有明确需求并定下语义后再实现。</p>
 */
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class AlarmRuleService {

    private static final Set<String> OPERATORS = Set.of("gte", "lte");
    private static final Set<String> LEVELS = Set.of("notice", "warning", "alarm");

    private final AlarmRuleMapper ruleMapper;
    private final MonitorPointMapper pointMapper;
    private final DataScopeService dataScope;

    /**
     * 规则列表。数据范围：全局规则（{@code point_id} 为空）对所有登录用户可见，
     * 加上挂在本用户可见测点上的规则——口径与理由见
     * {@link DataScopeService#alarmRuleFilter()}。
     *
     * <p>本方法只有控制器一个调用者，告警引擎走的是 {@code AlarmRuleMapper}，
     * 所以这里的范围断言不会影响告警评估（引擎没有登录上下文，真要在这儿取用户反而会出错）。</p>
     */
    public List<AlarmRuleVO> list() {
        LambdaQueryWrapper<AlarmRule> wrapper = dataScope.alarmRuleFilter();
        if (wrapper == null) {
            wrapper = new LambdaQueryWrapper<>();
        }
        List<AlarmRule> rules = ruleMapper.selectList(wrapper.orderByAsc(AlarmRule::getId));
        Set<Long> pointIds = rules.stream().map(AlarmRule::getPointId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> codes = pointIds.isEmpty() ? Map.of()
                : pointMapper.selectByIds(pointIds).stream()
                        .collect(Collectors.toMap(MonitorPoint::getId, MonitorPoint::getCode, (x, y) -> x));

        List<AlarmRuleVO> vos = new ArrayList<>(rules.size());
        for (AlarmRule r : rules) {
            AlarmRuleVO vo = toVO(r);
            vo.setPointCode(r.getPointId() == null ? null : codes.get(r.getPointId()));
            vos.add(vo);
        }
        return vos;
    }

    public AlarmRuleVO create(AlarmRuleRequest req) {
        validate(req);
        AlarmRule rule = new AlarmRule();
        apply(rule, req);
        ruleMapper.insert(rule);
        return withCode(rule);
    }

    public AlarmRuleVO update(Long id, AlarmRuleRequest req) {
        AlarmRule rule = require(id);
        validate(req);
        apply(rule, req);
        ruleMapper.updateById(rule);
        return withCode(rule);
    }

    public void delete(Long id) {
        require(id);
        ruleMapper.deleteById(id);
    }

    // ---------- 内部 ----------

    private void validate(AlarmRuleRequest req) {
        if (req == null || isBlank(req.getName())) {
            throw new BizException("规则名称不能为空");
        }
        if (isBlank(req.getMetricCode())) {
            throw new BizException("metricCode 不能为空");
        }
        if (req.getValue() == null) {
            throw new BizException("threshold 值不能为空");
        }
        if (req.getOperator() != null && !OPERATORS.contains(req.getOperator().toLowerCase())) {
            throw new BizException("operator 仅支持 gte / lte: " + req.getOperator());
        }
        // 只收 THRESHOLD：RATE/CHANGE 需要窗口聚合，引擎不评估。
        // 若放行，规则会以 THRESHOLD 语义被评估（拿原始值比阈值），
        // 用户以为建的是「速率」规则却按原始值告警——静默的错误行为，比拒绝建更危险
        if (req.getType() != null && !"THRESHOLD".equalsIgnoreCase(req.getType())) {
            throw new BizException("暂只支持 THRESHOLD 规则；速率类告警请对 rate_mm_d 建 THRESHOLD 规则"
                    + "（雷达已直接上报该测项）。收到 type: " + req.getType());
        }
        if (req.getLevel() != null && !LEVELS.contains(req.getLevel().toLowerCase())) {
            throw new BizException("level 仅支持 notice / warning / alarm: " + req.getLevel());
        }
    }

    private void apply(AlarmRule rule, AlarmRuleRequest req) {
        rule.setName(req.getName());
        rule.setPointId(req.getPointId());
        rule.setMetricCode(req.getMetricCode());
        rule.setRuleType(req.getType() == null ? "THRESHOLD" : req.getType().toUpperCase());
        rule.setOperator(req.getOperator() == null ? "gte" : req.getOperator().toLowerCase());
        rule.setThresholdValue(req.getValue());
        rule.setWindowMinutes(req.getWindowMinutes());
        rule.setRecoveryValue(req.getRecoveryValue());
        rule.setAlarmLevel(req.getLevel() == null ? "warning" : req.getLevel().toLowerCase());
        rule.setRepeatSuppressSeconds(req.getRepeatSuppressSeconds());
        rule.setEnabled(req.getEnabled() == null ? Boolean.TRUE : req.getEnabled());
    }

    private AlarmRule require(Long id) {
        AlarmRule rule = id == null ? null : ruleMapper.selectById(id);
        if (rule == null) {
            throw new BizException(404, "告警规则不存在: " + id);
        }
        return rule;
    }

    private AlarmRuleVO withCode(AlarmRule rule) {
        AlarmRuleVO vo = toVO(rule);
        if (rule.getPointId() != null) {
            MonitorPoint p = pointMapper.selectById(rule.getPointId());
            vo.setPointCode(p == null ? null : p.getCode());
        }
        return vo;
    }

    private AlarmRuleVO toVO(AlarmRule r) {
        AlarmRuleVO vo = new AlarmRuleVO();
        vo.setId(r.getId());
        vo.setName(r.getName());
        vo.setPointId(r.getPointId());
        vo.setMetricCode(r.getMetricCode());
        vo.setType(r.getRuleType());
        vo.setOperator(r.getOperator());
        vo.setValue(r.getThresholdValue());
        vo.setWindowMinutes(r.getWindowMinutes());
        vo.setLevel(r.getAlarmLevel());
        vo.setRecoveryValue(r.getRecoveryValue());
        vo.setRepeatSuppressSeconds(r.getRepeatSuppressSeconds());
        vo.setEnabled(r.getEnabled());
        return vo;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
