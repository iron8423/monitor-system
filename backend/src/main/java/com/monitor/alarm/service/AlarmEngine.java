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
import com.monitor.scope.service.DataScopeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 告警引擎：测量值落库后按规则评估，产生 / 自动解除警情（M2 闭环起点）。
 *
 * <p>规则语义（{@code THRESHOLD}）：{@code gte} 值 ≥ 阈值触发、值 ≤ 恢复值解除；
 * {@code lte} 值 ≤ 阈值触发、值 ≥ 恢复值解除。恢复值缺失则只触发不自动解除。</p>
 *
 * <p>两道闸门：① 质量标记为 {@code SUSPECT}/{@code FAULT} 的数据不参与判定
 * （message-contract §3）；② 同规则同测点在 {@code repeatSuppressSeconds} 内已产生过警情则不再触发（防刷屏）。</p>
 *
 * <p><b>警情唯一性与升级</b>（验收第 3 条「等级升高能升级」）：
 * 同一<b>测点 + 测项</b>未解除的警情**至多一条**。值继续恶化到更高等级规则的阈值时，
 * 不另开一条平行警情，而是把已有那条**就地升级**（等级抬高 + 时间线追加 {@code escalate}），
 * 运维看到的是「这个点在恶化」，而不是两条都要处置的警情。</p>
 *
 * <p>作用域为什么带「测项」：不同测项是不同的物理量，一条速率警情不该把同点的形变警情挡住。
 * 恢复也仍按**最初触发那条规则**的恢复阈值判定（升级只改等级，不改所属规则）——
 * 「恢复」的含义是回到最初越限的那个条件以内。</p>
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
    private final DataScopeService dataScope;

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
        // 该测点该测项未解除的警情（至多一条），整轮评估共用一份；触发/升级/恢复后就地更新，
        // 免得同一批规则内前后看到的 open 不一致
        Alarm open = findOpen(pointId, rules);
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
            if (open != null) {
                if (Objects.equals(open.getRuleId(), rule.getId())) {
                    // 只有最初触发的那条规则能解除它
                    if (shouldRecover(rule, v)) {
                        recover(open, metricCode, v);
                        open = null;
                    }
                } else if (shouldTrigger(rule, v)
                        && AlarmConstants.rankOf(rule.getAlarmLevel()) > AlarmConstants.rankOf(open.getAlarmLevel())) {
                    // 升级不看 suppressed：抑制是「别为同一条规则反复开新警情」，
                    // 而这里并没有开新警情；因为抑制而把已经到 +6.0 的点按在 warning 上才是错的。
                    escalate(open, rule, metricCode, v);
                }
                continue;
            }
            if (shouldTrigger(rule, v) && !suppressed(pointId, rule)) {
                open = trigger(pointId, rule, metricCode, v);
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

    /**
     * 该测点**该测项**下尚未解除的警情（至多一条）。测项由 {@code rules} 的 ruleId 集合圈定
     * （全局规则会按测点各自成警情，所以只能按点找不能按规则找——按规则找就看不见升级了）。
     * <p>排除设备告警（{@code alarm_type = DEVICE}）：那类警情挂设备、无测点，不参与形变判定。</p>
     */
    private Alarm findOpen(Long pointId, List<AlarmRule> rules) {
        Set<Long> ruleIds = rules.stream()
                .map(AlarmRule::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ruleIds.isEmpty()) {
            return null;
        }
        return alarmMapper.selectOne(new LambdaQueryWrapper<Alarm>()
                .eq(Alarm::getPointId, pointId)
                .in(Alarm::getRuleId, ruleIds)
                .notIn(Alarm::getStatus, AlarmConstants.CLOSED)
                .orderByDesc(Alarm::getTriggeredAt)
                .orderByDesc(Alarm::getId)
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

    /** 开一条新警情，返回该对象（调用方持有它做后续升级判断）。 */
    private Alarm trigger(Long pointId, AlarmRule rule, String metricCode, BigDecimal v) {
        Alarm a = new Alarm();
        a.setAlarmType(AlarmConstants.TYPE_POINT);
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
        broadcaster.broadcastScoped(SseBroadcaster.EVENT_ALARM, AlarmEvent.of(a, pointCode(pointId)),
                () -> dataScope.projectIdsOfAlarm(a));
        log.info("告警触发 alarmId={} pointId={} rule={} value={}", a.getId(), pointId, rule.getName(), v);
        return a;
    }

    /**
     * 就地升级已有警情的等级（不新开警情、不改所属规则）。
     * 时间线记一条 {@code escalate}，让「什么时候升的、被哪条规则升的」可追溯。
     */
    private void escalate(Alarm a, AlarmRule rule, String metricCode, BigDecimal v) {
        String from = a.getAlarmLevel();
        a.setAlarmLevel(rule.getAlarmLevel());
        alarmMapper.updateById(a);

        String note = String.format("%s = %s 命中更高等级规则「%s」（%s %s），等级 %s -> %s",
                metricCode, plain(v), rule.getName(), rule.getOperator(),
                plain(rule.getThresholdValue()), from, rule.getAlarmLevel());
        actionMapper.insert(action(a.getId(), AlarmConstants.ACTION_ESCALATE, AlarmConstants.SYSTEM, note));
        broadcaster.broadcastScoped(SseBroadcaster.EVENT_ALARM, AlarmEvent.of(a, pointCode(a.getPointId())),
                () -> dataScope.projectIdsOfAlarm(a));
        log.info("告警升级 alarmId={} {} -> {} value={}", a.getId(), from, rule.getAlarmLevel(), v);
    }

    private void recover(Alarm a, String metricCode, BigDecimal v) {
        a.setStatus(AlarmConstants.RESOLVED);
        a.setResolvedAt(LocalDateTime.now());
        alarmMapper.updateById(a);
        actionMapper.insert(action(a.getId(), AlarmConstants.ACTION_RECOVER, AlarmConstants.SYSTEM,
                String.format("%s = %s 已回落至恢复阈值内，系统自动解除", metricCode, plain(v))));
        broadcaster.broadcastScoped(SseBroadcaster.EVENT_ALARM, AlarmEvent.of(a, pointCode(a.getPointId())),
                () -> dataScope.projectIdsOfAlarm(a));
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
