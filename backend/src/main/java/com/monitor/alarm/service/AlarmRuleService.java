package com.monitor.alarm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.monitor.alarm.dto.AlarmRuleRequest;
import com.monitor.alarm.dto.AlarmRuleVO;
import com.monitor.alarm.entity.AlarmRule;
import com.monitor.alarm.mapper.AlarmRuleMapper;
import com.monitor.common.exception.BizException;
import com.monitor.project.entity.MonitorPoint;
import com.monitor.project.entity.Project;
import com.monitor.project.mapper.MonitorPointMapper;
import com.monitor.project.mapper.ProjectMapper;
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
 *
 * <p><b>作用域（V22）</b>：{@code pointId} 非空 = 该测点专属；否则 {@code projectId} 非空 =
 * 该项目下所有测点；两者都空 = 全局。全局档在多场景（V20 起）是**危险默认值**——
 * 一套 ±3mm 会同时套到桥梁、路基与储罐基础上，阈值只对上了量纲、没对上结构，
 * 所以接口层不再把"没给作用域"当成一件理所当然的事：它仍然可用（老客户端兼容），
 * 但要在管理端明确选"全部项目"才写得出来，读回来也能一眼看出。</p>
 */
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class AlarmRuleService {

    private static final Set<String> OPERATORS = Set.of("gte", "lte");
    private static final Set<String> LEVELS = Set.of("notice", "warning", "alarm");

    private final AlarmRuleMapper ruleMapper;
    private final MonitorPointMapper pointMapper;
    private final ProjectMapper projectMapper;
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
        Set<Long> projectIds = rules.stream().map(AlarmRule::getProjectId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> projectNames = projectIds.isEmpty() ? Map.of()
                : projectMapper.selectByIds(projectIds).stream()
                        .collect(Collectors.toMap(Project::getId, Project::getName, (x, y) -> x));

        List<AlarmRuleVO> vos = new ArrayList<>(rules.size());
        for (AlarmRule r : rules) {
            AlarmRuleVO vo = toVO(r);
            vo.setPointCode(r.getPointId() == null ? null : codes.get(r.getPointId()));
            vo.setProjectName(r.getProjectId() == null ? null : projectNames.get(r.getProjectId()));
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

    /**
     * 更新规则（整条替换，未传的字段按 {@link #apply} 的默认值处理）。
     *
     * <p><b>改 {@code metricCode} / {@code pointId} 不会连带改已开警情</b>，这是刻意的：
     * 警情上的 {@code metric_code} 是「当时为什么开」的快照，规则改了不该把历史重新解说一遍
     * （同 {@code Alarm.alarmReason} 的口径）。后果是那条警情继续占着**原**测项的未解除键位，
     * 要有人处置它才会释放；同时它仍会被引擎按原测项找到，后续重建的规则可以接手升级。
     * 详见 {@link #delete} 与 {@code AlarmEngine.findOpen}。</p>
     */
    public AlarmRuleVO update(Long id, AlarmRuleRequest req) {
        AlarmRule rule = require(id);
        validate(req);
        apply(rule, req);
        ruleMapper.updateById(rule);
        return withCode(rule);
    }

    /**
     * 删除规则（**物理删除**，无软删标记）。
     *
     * <p>允许删除一条名下有未解除警情的规则，但要知道后果、且这个后果是**刻意接受**的：
     * 那条警情会一直挂在「未解除」上，直到有人处置它（{@code resolve}/{@code misreport}）。
     * 期间它会一直占着 {@code (测点, 测项)} 的未解除唯一键，所以该测项不会再开新警情——
     * <b>这不是 bug，正是「同一测点同一测项至多一条未解除警情」这条不变式的直接推论</b>：
     * 那里确实欠着一条没人处理的警情，再开一条只会让运维看到两条。</p>
     *
     * <p>为什么不做「删除时自动关掉它的警情」：那会**静默抹掉一条真实发生过的越限记录**，
     * 而删除规则的动因往往是「规则配错了」，不是「这个测点不再越限了」。宁可留一条要人处理的
     * 警情，也不要自动改写历史。同理也不在这里拦截——删除是正当操作，把处置责任交回给人。</p>
     *
     * <p>V14 起引擎按 {@code (point_id, metric_code)} 找未解除警情（不再按 rule_id），
     * 所以删掉规则不会让那条警情从引擎视野里消失、变成谁都看不见却一直占位的僵尸，
     * 后续重建同名测项的规则仍能接手升级它。见 {@code AlarmEngine.findOpen} 的说明。</p>
     */
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

        // 作用域校验（V22）：引用必须存在，且两者同时给出时不能互相矛盾。
        // 不做静默纠正（如"按测点自动改项目"）：调用方以为规则建在 A 上、实际落在 B 上，
        // 是比 400 难查得多的错误——阈值错了要等下一次越限才发现。
        MonitorPoint point = req.getPointId() == null ? null : pointMapper.selectById(req.getPointId());
        if (req.getPointId() != null && point == null) {
            throw new BizException("测点不存在: " + req.getPointId());
        }
        if (req.getProjectId() != null && projectMapper.selectById(req.getProjectId()) == null) {
            throw new BizException("项目不存在: " + req.getProjectId());
        }
        if (point != null && req.getProjectId() != null) {
            Long actual = pointMapper.selectProjectIdOfPoint(point.getId());
            if (!req.getProjectId().equals(actual)) {
                throw new BizException("测点 " + point.getCode() + " 不属于项目 " + req.getProjectId()
                        + "（实际归属：" + (actual == null ? "无" : actual) + "）；点专属规则不需要再给项目作用域");
            }
        }
    }

    private void apply(AlarmRule rule, AlarmRuleRequest req) {
        rule.setName(req.getName());
        rule.setPointId(req.getPointId());
        rule.setProjectId(req.getProjectId());
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
        if (rule.getProjectId() != null) {
            Project p = projectMapper.selectById(rule.getProjectId());
            vo.setProjectName(p == null ? null : p.getName());
        }
        return vo;
    }

    private AlarmRuleVO toVO(AlarmRule r) {
        AlarmRuleVO vo = new AlarmRuleVO();
        vo.setId(r.getId());
        vo.setName(r.getName());
        vo.setPointId(r.getPointId());
        vo.setProjectId(r.getProjectId());
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
