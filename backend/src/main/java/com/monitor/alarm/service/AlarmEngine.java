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
import com.monitor.common.concurrent.KeyLock;
import com.monitor.common.sse.SseBroadcaster;
import com.monitor.config.AlarmEvalTx;
import com.monitor.project.entity.MonitorPoint;
import com.monitor.project.mapper.MonitorPointMapper;
import com.monitor.scope.service.DataScopeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
 * <h3>并发（清单第 32 条）</h3>
 * <p>上面那句「至多一条」在 V14 之前只是应用层约定：{@link #findOpen} 是 check-then-act，
 * 两个线程同时读到「没有未解除警情」就各开一条；而且 {@code alarm} 表上一个唯一约束都没有，
 * 多实例部署下更是完全失效。现在三层一起兜：</p>
 * <ol>
 *   <li><b>库级兜底</b>——{@code alarm.open_key} 上的唯一索引（V14）。
 *       这是唯一在「多实例 / 多进程」下仍成立的保证，另外两层都只是优化；</li>
 *   <li><b>进程内串行</b>——{@link KeyLock} 按 {@code P:<pointId>:<metricCode>} 分段加锁，
 *       让第二个线程能读到第一个刚提交的警情，走到升级 / 恢复而不是重复触发；</li>
 *   <li><b>写时 CAS</b>——升级与关闭都带前置条件（{@code status}、{@code alarm_level}），
 *       读到过期数据的一方拿到 0 行、放弃写入而不是覆盖。见 {@code AlarmMapper}。</li>
 * </ol>
 * <p><b>锁必须跨过提交</b>，否则第 2 层等于没做：评估若留在调用方事务里，锁在
 * {@link #doEvaluate} 返回时就释放了而警情还没 COMMIT，下一个线程照样读不到。
 * 所以写入路径走 {@link AlarmEvalTx} 的独立短事务，锁区间的末端才真正落在提交之后——
 * 这两者是一个整体，改一处等于没改。</p>
 * <p>纯读路径（绝大多数测值）**行为与改造前完全一致**：仍是那两条查询、不开事务、不持锁。
 * 只有 {@link #mightWrite} 预判「这次可能写」时才付出额外连接的代价，见该方法的说明。</p>
 *
 * <p>只评估 {@code THRESHOLD}。{@code RATE}/{@code CHANGE} 需 {@code windowMinutes} 窗口聚合，
 * 接口层已拒绝创建（见 {@code AlarmRuleService#validate}）；引擎这里再过滤一次，
 * 防直接写库的遗留行被当成 {@code THRESHOLD} 评估——那会让一条声明为「速率」的规则
 * 拿原始值去比阈值，错误是静默的。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlarmEngine {

    private final AlarmRuleMapper ruleMapper;
    private final AlarmMapper alarmMapper;
    private final AlarmActionMapper actionMapper;
    private final MonitorPointMapper pointMapper;
    private final SseBroadcaster broadcaster;
    private final DataScopeService dataScope;
    private final KeyLock keyLock;
    private final AlarmEvalTx alarmEvalTx;

    /**
     * 等锁上限。取值理由：持锁段是「读两条 + 写两三条」的本地库操作，
     * 正常情况下是毫秒级；3 秒意味着对端已经严重异常（连接泄漏、锁表）。
     * 到点宁可无锁执行（唯一索引兜底）也不要让接入线程继续陪等——
     * 接入是有 SLA 的主流程，告警不该拖住它。
     */
    private static final long LOCK_TIMEOUT_MS = 3000L;

    /** 已就「类型不参与评估」告过警的规则，避免每条测值刷屏。 */
    private final Set<Long> warnedTypes = ConcurrentHashMap.newKeySet();

    /** 对一条刚落库的测值做规则评估。 */
    public void evaluate(Long pointId, String metricCode, Double value, String quality) {
        try {
            evaluateGuarded(pointId, metricCode, value, quality);
        } catch (Exception e) {
            log.error("告警评估失败 pointId={} metricCode={} value={}", pointId, metricCode, value, e);
        }
    }

    private void evaluateGuarded(Long pointId, String metricCode, Double value, String quality) {
        if (pointId == null || metricCode == null || value == null || !Double.isFinite(value)) {
            return;
        }
        if ("SUSPECT".equalsIgnoreCase(quality) || "FAULT".equalsIgnoreCase(quality)) {
            return;
        }

        List<AlarmRule> rules = enabledRulesFor(pointId, metricCode);
        if (rules.isEmpty()) {
            return;
        }
        // 「规则类型不参与评估」的告警放在这里而不是写入路径里：它对每条测值都该生效一次，
        // 放进只有可能写入时才进得去的 doEvaluate，会让一条**永远不越限**的非 THRESHOLD 规则
        // 悄无声息地被忽略（正是这条日志要防的事）。warnedTypes 保证每规则只记一次。
        for (AlarmRule rule : rules) {
            warnIfUnevaluable(rule);
        }
        BigDecimal v = BigDecimal.valueOf(value);

        // 预判这一轮到底会不会写。不会写就直接返回——纯读路径保持改造前的开销
        // （同样是这两条查询，不开事务、不持锁、不额外占用连接）。
        // 这一层是**必须**的：写入路径要开 REQUIRES_NEW，而外层接入事务此时正握着一条连接，
        // 内层再取一条 → 一个并发接入线程占两条。Hikari 默认池只有 10，无脑开事务在
        // 5 个并发接入下就会打满并互相等待。见 mightWrite 的保守性论证。
        if (!mightWrite(rules, findOpen(pointId, metricCode), v)) {
            return;
        }

        String key = AlarmConstants.openKeyOfPoint(pointId, metricCode);
        keyLock.runLocked(key, LOCK_TIMEOUT_MS,
                () -> alarmEvalTx.run(() -> doEvaluate(pointId, metricCode, v)));
    }

    /**
     * 该测点该测项下启用中的规则：全局规则 / 本项目规则 / 该测点专属规则的并集。
     *
     * <p>V22 起规则有项目作用域，三档的匹配顺序是：点档（{@code point_id} 非空）按点命中、
     * 项目档（{@code point_id} 空、{@code project_id} 非空）按测点归属的项目命中、
     * 两列都空是全局档（兼容 V22 之前的种子形状）。</p>
     *
     * <p><b>为什么先查规则、再按需解析归属</b>：项目档与全局档在 {@code point_id} 上都是 NULL，
     * 所以那条查询天然把两者一起取回来；只有在候选里**确实出现**了项目档规则时才需要知道
     * "这个测点属于哪个项目"。绝大多数部署里没有项目档规则（或该测项没有），
     * 于是这条热路径的查询数与 V22 之前完全一致，多出来的那条单行 JOIN
     * （{@link MonitorPointMapper#selectProjectIdOfPoint}）是可解释的、按需付出的代价。</p>
     *
     * <p><b>归属解析不出来时按"只匹配全局档"处理</b>：测点不存在、对象/场景被软删、
     * 项目列为空——这些情况下没有任何依据说这个测点属于哪个项目，
     * 于是项目档规则一律不参与。方向是 fail-closed：宁可少报（并留下可查的档案异常），
     * 也不要把甲项目的阈值套到乙项目的测点上。</p>
     */
    private List<AlarmRule> enabledRulesFor(Long pointId, String metricCode) {
        List<AlarmRule> candidates = ruleMapper.selectList(new LambdaQueryWrapper<AlarmRule>()
                .eq(AlarmRule::getEnabled, true)
                .eq(AlarmRule::getMetricCode, metricCode)
                .and(w -> w.isNull(AlarmRule::getPointId).or().eq(AlarmRule::getPointId, pointId)));
        if (candidates.stream().noneMatch(r -> r.getProjectId() != null)) {
            return candidates;
        }
        Long projectId = pointMapper.selectProjectIdOfPoint(pointId);
        List<AlarmRule> matched = new ArrayList<>(candidates.size());
        for (AlarmRule r : candidates) {
            if (r.getPointId() != null) {
                matched.add(r);                                  // 点档：projectId 只是档案，不参与匹配
            } else if (r.getProjectId() == null || r.getProjectId().equals(projectId)) {
                matched.add(r);                                  // 全局档 / 命中归属的项目档
            }
        }
        return matched;
    }

    /**
     * 「这一轮评估有没有可能写库」的**保守**预判：只在能确定「绝不写」时才返回 false。
     *
     * <p>保守性的要求是单向的——<b>宁可多返回 true（白开一次短事务），绝不能漏返回 false</b>
     * （那会让一条已经越限的测值完全不被评估，是漏报）。所以这里逐条覆盖
     * {@link #doEvaluate} 循环里的每个写入分支，且刻意**不比等级、不看抑制窗口**：</p>
     * <ul>
     *   <li>{@code trigger}——需要 {@code shouldTrigger}，本方法直接查它；</li>
     *   <li>{@code escalate}——除 {@code shouldTrigger} 外还要求等级更高，
     *       少比一次等级只会让判定更宽，不会更窄；</li>
     *   <li>{@code recover}——需要 {@code shouldRecover} 且规则就是 open 的所属规则，
     *       本方法只查 {@code shouldRecover}，连「是不是同一条规则」都不比，同样只会更宽。</li>
     * </ul>
     * <p>刻意不在预判里调用 {@code suppressed(...)}：那是**第三条**查询，
     * 而它的作用只是把「会写」缩成「不会写」——对保守性毫无帮助，却让每个测值都多一次查询。
     * 这个取舍是有意的：预判要便宜，不然它省下的连接开销会被自己的查询吃掉。</p>
     *
     * @param open 该测点该测项未解除的警情，可为 null（那就不可能升级 / 恢复，只剩触发一种可能）
     */
    private boolean mightWrite(List<AlarmRule> rules, Alarm open, BigDecimal v) {
        for (AlarmRule rule : rules) {
            if (!isEvaluable(rule)) {
                continue;
            }
            if (shouldTrigger(rule, v)) {
                return true;                 // trigger 或 escalate 都以此为必要条件
            }
            if (open != null && shouldRecover(rule, v)) {
                return true;                 // 可能是 open 所属规则的自动解除
            }
        }
        return false;
    }

    /**
     * 真正评估一轮。**必须**在 {@link KeyLock} 的锁内、且由 {@link AlarmEvalTx} 开的新事务里执行，
     * 否则 {@link #findOpen} 又会退化成跨不过提交的 check-then-act（见类注释）。
     *
     * <p>规则在这里**重新查一次**而不是沿用预判时那份：新事务里读到的是最新规则，
     * 而这次查询只发生在罕见的写入路径上，代价可以忽略。</p>
     */
    private void doEvaluate(Long pointId, String metricCode, BigDecimal v) {
        List<AlarmRule> rules = enabledRulesFor(pointId, metricCode);
        if (rules.isEmpty()) {
            return;
        }

        // 该测点该测项未解除的警情（至多一条），整轮评估共用一份；触发/升级/恢复后就地更新，
        // 免得同一批规则内前后看到的 open 不一致
        Alarm open = findOpen(pointId, metricCode);
        for (AlarmRule rule : rules) {
            if (!isEvaluable(rule)) {
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

    /**
     * 直接写库 / 历史遗留的非 THRESHOLD 规则会被忽略。不记这一条的话，
     * 「规则建了却不按它声明的类型生效」没有任何痕迹——接口层已拦（{@code AlarmRuleService#validate}），
     * 这里是兜底。{@code warnedTypes} 保证每条规则只记一次，否则每条测值都会刷一行。
     */
    private void warnIfUnevaluable(AlarmRule rule) {
        if (!isEvaluable(rule) && rule.getId() != null && warnedTypes.add(rule.getId())) {
            log.warn("规则类型 {} 不参与评估，将被忽略: ruleId={} name={}",
                    rule.getRuleType(), rule.getId(), rule.getName());
        }
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
     * 该测点**该测项**下尚未解除的警情（至多一条）。
     *
     * <p>判据是 {@code (point_id, metric_code, status)}，<b>与 V14 唯一索引的语义逐字对应</b>。
     * 这一点是刻意的：这里的 SELECT 和那条约束必须表达同一个命题，
     * 一旦两者不一致，要么约束把引擎认为「可以插入」的行拒掉（表现为整轮评估静默失败），
     * 要么引擎认为「已经有了」的行约束不管（表现为重复警情）。</p>
     *
     * <p>V14 之前这里是用 {@code rule_id IN (该测项的规则)} 间接表达的（当时警情上没有测项列）。
     * 改用测项列之后多了一个必要的好处：<b>规则被删掉或改了测项，这条警情仍然找得到</b>。
     * 按 rule_id 找的话，规则一删，那条未解除警情就从引擎视野里消失——它会一直占着键位，
     * 却永远不被评估，该测项此后既开不出新警情也不会有升级。</p>
     *
     * <p>排除设备告警（{@code alarm_type = DEVICE}）：那类警情挂设备、无测点、{@code metric_code} 为空，
     * 天然不匹配本判据，无需额外条件。</p>
     */
    private Alarm findOpen(Long pointId, String metricCode) {
        return alarmMapper.selectOne(new LambdaQueryWrapper<Alarm>()
                .eq(Alarm::getPointId, pointId)
                .eq(Alarm::getMetricCode, metricCode)
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
        // 测项副本 + 未解除唯一键（V14）。两者必须与 V14 迁移拼出的值一致，
        // 所以统一走 AlarmConstants 的工厂方法而不是在这里拼字符串。
        a.setMetricCode(metricCode);
        a.setOpenKey(AlarmConstants.openKeyOfPoint(pointId, metricCode));
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
        String to = rule.getAlarmLevel();
        // CAS：前置条件带上当前状态与当前等级。本方法在 KeyLock 与独立短事务里执行，
        // 正常情况下不会失配；失配意味着有别处（人工处置、另一个实例）刚动过这条警情，
        // 那时**不写时间线**——记一条实际没发生过的升级，比漏记更难排查。
        int rows = alarmMapper.escalateAlarm(a.getId(), a.getStatus(), from, to, LocalDateTime.now());
        if (rows == 0) {
            log.info("告警升级跳过（等级或状态已被改变）alarmId={} 期望 {}/{} -> {}", a.getId(), a.getStatus(), from, to);
            return;
        }
        a.setAlarmLevel(to);

        String note = String.format("%s = %s 命中更高等级规则「%s」（%s %s），等级 %s -> %s",
                metricCode, plain(v), rule.getName(), rule.getOperator(),
                plain(rule.getThresholdValue()), from, to);
        actionMapper.insert(action(a.getId(), AlarmConstants.ACTION_ESCALATE, AlarmConstants.SYSTEM, note));
        broadcaster.broadcastScoped(SseBroadcaster.EVENT_ALARM, AlarmEvent.of(a, pointCode(a.getPointId())),
                () -> dataScope.projectIdsOfAlarm(a));
        log.info("告警升级 alarmId={} {} -> {} value={}", a.getId(), from, to, v);
    }

    private void recover(Alarm a, String metricCode, BigDecimal v) {
        LocalDateTime now = LocalDateTime.now();
        // 关闭必须走 closeAlarm 而不是 updateById：后者跳过 null 字段，写不出 open_key = NULL，
        // 那条警情会永久占住未解除键位，把该测点该测项**后续所有警情**挡在唯一索引之外。
        int rows = alarmMapper.closeAlarm(a.getId(), a.getStatus(), AlarmConstants.RESOLVED, now, now);
        if (rows == 0) {
            log.info("告警自动解除跳过（状态已被改变）alarmId={} 期望 {}", a.getId(), a.getStatus());
            return;
        }
        a.setStatus(AlarmConstants.RESOLVED);
        a.setResolvedAt(now);
        a.setOpenKey(null);

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
