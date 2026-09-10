package com.monitor.alarm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.monitor.alarm.AlarmConstants;
import com.monitor.alarm.dto.AlarmEvent;
import com.monitor.alarm.entity.Alarm;
import com.monitor.alarm.entity.AlarmAction;
import com.monitor.alarm.entity.AlarmRule;
import com.monitor.alarm.mapper.AlarmActionMapper;
import com.monitor.alarm.mapper.AlarmMapper;
import com.monitor.alarm.mapper.AlarmRuleMapper;
import com.monitor.common.sse.SseBroadcaster;
import com.monitor.project.entity.MonitorPoint;
import com.monitor.project.mapper.MonitorPointMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警引擎：测量值落库后按规则评估，产生 / 自动解除警情（M2 闭环起点）。
 *
 * <p>规则语义（{@code THRESHOLD}）：{@code gte} 值 ≥ 阈值触发、值 ≤ 恢复值解除；
 * {@code lte} 值 ≤ 阈值触发、值 ≥ 恢复值解除。恢复值缺失则只触发不自动解除。</p>
 *
 * <p>两道闸门：① 质量标记为 {@code SUSPECT}/{@code FAULT} 的数据不参与判定
 * （message-contract §3）；② 同规则同测点在 {@code repeatSuppressSeconds} 内已产生过警情则不再触发（防刷屏）。</p>
 *
 * <p>评估异常一律吞掉并记日志——接入是主流程，不能因告警判定失败而回滚落库。</p>
 *
 * <p>只评估 {@code THRESHOLD}。{@code RATE}/{@code CHANGE} 需 {@code windowMinutes} 窗口聚合，
 * 接口层已拒绝创建（见 {@code AlarmRuleService#validate}）；引擎这里再过滤一次，
 * 防直接写库的遗留行被当成 {@code THRESHOLD} 评估——那会让一条声明为「速率」的规则
 * 拿原始值去比阈值，错误是静默的。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class AlarmEngine {

    private final AlarmRuleMapper ruleMapper;
    private final AlarmMapper alarmMapper;
    private final AlarmActionMapper actionMapper;
    private final MonitorPointMapper pointMapper;
    private final SseBroadcaster broadcaster;

    /** 已就「类型不参与评估」告过警的规则，避免每条测值刷屏。 */
    private final Set<Long> warnedTypes = ConcurrentHashMap.newKeySet();

    /** 对一条刚落库的测值做规则评估。 */
    public void evaluate(Long pointId, String metricCode, Double value, String quality) {
        try {
            doEvaluate(pointId, metricCode, value, quality);
        } catch (Exception e) {
            log.error("告警评估失败 pointId={} metricCode={} value={}", pointId, metricCode, value, e);
        }
    }

    private void doEvaluate(Long pointId, String metricCode, Double value, String quality) {
        if (pointId == null || metricCode == null || value == null || !Double.isFinite(value)) {
            return;
        }
        if ("SUSPECT".equalsIgnoreCase(quality) || "FAULT".equalsIgnoreCase(quality)) {
            return;
        }

        List<AlarmRule> rules = ruleMapper.selectList(new LambdaQueryWrapper<AlarmRule>()
                .eq(AlarmRule::getEnabled, true)
                .eq(AlarmRule::getMetricCode, metricCode)
                .and(w -> w.isNull(AlarmRule::getPointId).or().eq(AlarmRule::getPointId, pointId)));
        if (rules.isEmpty()) {
            return;
        }

        BigDecimal v = BigDecimal.valueOf(value);
        for (AlarmRule rule : rules) {
            if (!isEvaluable(rule)) {
                // 直接写库/历史遗留的非 THRESHOLD 规则会被忽略。不记这一条的话，
                // 「规则建了却不按它声明的类型生效」没有任何痕迹——接口层已拦，这里是兜底
                if (rule.getId() != null && warnedTypes.add(rule.getId())) {
                    log.warn("规则类型 {} 不参与评估，将被忽略: ruleId={} name={}",
                            rule.getRuleType(), rule.getId(), rule.getName());
                }
                continue;
            }
            Alarm open = findOpen(pointId, rule.getId());
            if (open != null) {
                if (shouldRecover(rule, v)) {
                    recover(open, metricCode, v);
                }
                continue;
            }
            if (shouldTrigger(rule, v) && !suppressed(pointId, rule)) {
                trigger(pointId, rule, metricCode, v);
            }
        }
    }

    /**
     * 只有 {@code THRESHOLD} 参与评估。{@code ruleType} 为空的旧数据按 {@code THRESHOLD} 处理
     * （接口层已不接受其他类型，见 {@code AlarmRuleService#validate}）。
     */
    private static boolean isEvaluable(AlarmRule rule) {
        return rule.getRuleType() == null || "THRESHOLD".equalsIgnoreCase(rule.getRuleType());
    }

    private boolean shouldTrigger(AlarmRule r, BigDecimal v) {
        if (r.getThresholdValue() == null) {
            return false;
        }
        return "lte".equalsIgnoreCase(r.getOperator())
                ? v.compareTo(r.getThresholdValue()) <= 0
                : v.compareTo(r.getThresholdValue()) >= 0;
    }

    private boolean shouldRecover(AlarmRule r, BigDecimal v) {
        if (r.getRecoveryValue() == null) {
            return false;
        }
        return "lte".equalsIgnoreCase(r.getOperator())
                ? v.compareTo(r.getRecoveryValue()) >= 0
                : v.compareTo(r.getRecoveryValue()) <= 0;
    }

    /** 该测点该规则下尚未解除的警情（全局规则会按测点各自成警情）。 */
    private Alarm findOpen(Long pointId, Long ruleId) {
        return alarmMapper.selectOne(new LambdaQueryWrapper<Alarm>()
                .eq(Alarm::getPointId, pointId)
                .eq(Alarm::getRuleId, ruleId)
                .notIn(Alarm::getStatus, AlarmConstants.CLOSED)
                .orderByDesc(Alarm::getTriggeredAt)
                .last("LIMIT 1"));
    }

    /** 防刷屏：抑制窗口内同规则同测点已产生过警情（含已解除的）。 */
    private boolean suppressed(Long pointId, AlarmRule rule) {
        Integer secs = rule.getRepeatSuppressSeconds();
        if (secs == null || secs <= 0) {
            return false;
        }
        return alarmMapper.selectCount(new LambdaQueryWrapper<Alarm>()
                .eq(Alarm::getPointId, pointId)
                .eq(Alarm::getRuleId, rule.getId())
                .ge(Alarm::getCreatedAt, LocalDateTime.now().minusSeconds(secs))) > 0;
    }

    private void trigger(Long pointId, AlarmRule rule, String metricCode, BigDecimal v) {
        Alarm a = new Alarm();
        a.setPointId(pointId);
        a.setRuleId(rule.getId());
        a.setAlarmLevel(rule.getAlarmLevel());
        a.setStatus(AlarmConstants.PENDING);
        a.setTriggerValue(v);
        a.setTriggeredAt(LocalDateTime.now());
        alarmMapper.insert(a);

        String note = String.format("%s = %s 触发规则「%s」（%s %s）", metricCode, plain(v),
                rule.getName(), rule.getOperator(), plain(rule.getThresholdValue()));
        actionMapper.insert(action(a.getId(), AlarmConstants.ACTION_TRIGGER, AlarmConstants.SYSTEM, note));
        broadcaster.broadcast(SseBroadcaster.EVENT_ALARM, AlarmEvent.of(a, pointCode(pointId)));
        log.info("告警触发 alarmId={} pointId={} rule={} value={}", a.getId(), pointId, rule.getName(), v);
    }

    private void recover(Alarm a, String metricCode, BigDecimal v) {
        a.setStatus(AlarmConstants.RESOLVED);
        a.setResolvedAt(LocalDateTime.now());
        alarmMapper.updateById(a);
        actionMapper.insert(action(a.getId(), AlarmConstants.ACTION_RECOVER, AlarmConstants.SYSTEM,
                String.format("%s = %s 已回落至恢复阈值内，系统自动解除", metricCode, plain(v))));
        broadcaster.broadcast(SseBroadcaster.EVENT_ALARM, AlarmEvent.of(a, pointCode(a.getPointId())));
        log.info("告警自动解除 alarmId={} value={}", a.getId(), v);
    }

    /** 事件载荷要带业务点号（D10：id 数值 / code 字符串）。 */
    private String pointCode(Long pointId) {
        MonitorPoint p = pointId == null ? null : pointMapper.selectById(pointId);
        return p == null ? null : p.getCode();
    }

    private AlarmAction action(Long alarmId, String type, String operator, String note) {
        AlarmAction act = new AlarmAction();
        act.setAlarmId(alarmId);
        act.setActionType(type);
        act.setOperator(operator);
        act.setNote(note);
        return act;
    }

    private static String plain(BigDecimal v) {
        return v == null ? "null" : v.stripTrailingZeros().toPlainString();
    }
}
