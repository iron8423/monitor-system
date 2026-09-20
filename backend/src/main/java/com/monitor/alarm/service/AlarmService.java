package com.monitor.alarm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.monitor.alarm.AlarmConstants;
import com.monitor.alarm.dto.AlarmActionRequest;
import com.monitor.alarm.dto.AlarmDetailVO;
import com.monitor.alarm.dto.AlarmEvent;
import com.monitor.alarm.dto.AlarmVO;
import com.monitor.alarm.entity.Alarm;
import com.monitor.alarm.entity.AlarmAction;
import com.monitor.alarm.entity.AlarmRule;
import com.monitor.alarm.mapper.AlarmActionMapper;
import com.monitor.alarm.mapper.AlarmMapper;
import com.monitor.alarm.mapper.AlarmRuleMapper;
import com.monitor.asset.DeviceStatusPolicy;
import com.monitor.asset.entity.Device;
import com.monitor.asset.mapper.DeviceMapper;
import com.monitor.common.PageResult;
import com.monitor.common.exception.BizException;
import com.monitor.common.sse.SseBroadcaster;
import com.monitor.common.util.Times;
import com.monitor.project.entity.MonitorPoint;
import com.monitor.project.mapper.MonitorPointMapper;
import com.monitor.scope.service.DataScopeService;
import com.monitor.telemetry.DataQualityPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 警情查询与处置（《B侧接口契约_M0》§4）。
 */
@Service
@RequiredArgsConstructor
public class AlarmService {

    private final AlarmMapper alarmMapper;
    private final AlarmActionMapper actionMapper;
    private final AlarmRuleMapper ruleMapper;
    private final MonitorPointMapper pointMapper;
    private final DeviceMapper deviceMapper;
    private final SseBroadcaster broadcaster;
    private final DataScopeService dataScope;

    /**
     * 警情列表，支持 level / status / pointId / deviceId / alarmType / from / to 筛选。
     *
     * <p>{@code alarmType}（{@code POINT}/{@code DEVICE}）用于只看某一类——
     * 形变警情和设备离线告警在同一张表里，前端通常要分开看。</p>
     */
    public PageResult<AlarmVO> list(String level, String status, Long pointId, Long deviceId,
                                    String alarmType, String from, String to,
                                    long pageNum, long pageSize) {
        LocalDateTime f = Times.parse(from, "from");
        LocalDateTime t = Times.parse(to, "to");
        // 先归一化再进条件：eq(condition, ...) 的参数是**先求值**的，
        // 写成 eq(notBlank(x), col, x.trim()) 会在 x 为 null 时 NPE——条件为 false 也拦不住。
        String type = notBlank(alarmType) ? alarmType.trim().toUpperCase() : null;

        LambdaQueryWrapper<Alarm> wrapper = new LambdaQueryWrapper<Alarm>()
                .eq(notBlank(level), Alarm::getAlarmLevel, level)
                .eq(notBlank(status), Alarm::getStatus, status)
                .eq(pointId != null, Alarm::getPointId, pointId)
                .eq(deviceId != null, Alarm::getDeviceId, deviceId)
                .eq(type != null, Alarm::getAlarmType, type)
                .ge(f != null, Alarm::getTriggeredAt, f)
                .le(t != null, Alarm::getTriggeredAt, t);
        // 数据范围必须在 orderBy 之前挂：MyBatis-Plus 按调用顺序拼 SQL，
        // 条件加在 orderByDesc 之后会被拼到 ORDER BY 后面，变成语法错误。
        // 范围本身必须进 SQL 而不是查完再过滤——本端点分页，内存过滤会让
        // total 与实际可见条数不一致（翻到第 2 页可能一条都没有）。
        dataScope.applyAlarmScope(wrapper);
        wrapper.orderByDesc(Alarm::getTriggeredAt).orderByDesc(Alarm::getId);

        Page<Alarm> page = alarmMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);

        List<Alarm> records = page.getRecords();
        Map<Long, String> codes = pointCodes(records.stream().map(Alarm::getPointId).collect(Collectors.toSet()));
        Map<Long, String> devCodes = deviceCodes(records.stream().map(Alarm::getDeviceId).collect(Collectors.toSet()));
        Map<Long, AlarmAction> lastActions =
                lastActions(records.stream().map(Alarm::getId).collect(Collectors.toSet()));

        List<AlarmVO> vos = new ArrayList<>(records.size());
        for (Alarm a : records) {
            AlarmVO vo = new AlarmVO();
            vo.setId(a.getId());
            vo.setAlarmType(a.getAlarmType());
            vo.setPointId(a.getPointId());
            vo.setPointCode(a.getPointId() == null ? null : codes.get(a.getPointId()));
            vo.setDeviceId(a.getDeviceId());
            vo.setDeviceCode(a.getDeviceId() == null ? null : devCodes.get(a.getDeviceId()));
            vo.setAlarmReason(a.getAlarmReason());
            vo.setLevel(a.getAlarmLevel());
            vo.setStatus(a.getStatus());
            vo.setTriggeredAt(Times.iso(a.getTriggeredAt()));
            AlarmAction act = lastActions.get(a.getId());
            if (act != null) {
                vo.setLastAction(act.getActionType());
                vo.setLastActionAt(Times.iso(act.getCreatedAt()));
            }
            vos.add(vo);
        }

        PageResult<AlarmVO> result = new PageResult<>();
        result.setTotal(page.getTotal());
        result.setPageNum(page.getCurrent());
        result.setPageSize(page.getSize());
        result.setRecords(vos);
        return result;
    }

    /** 警情详情：触发快照 + 处置时间线。 */
    public AlarmDetailVO detail(Long id) {
        Alarm a = require(id);
        AlarmDetailVO vo = new AlarmDetailVO();
        vo.setId(a.getId());
        vo.setAlarmType(a.getAlarmType());
        vo.setPointId(a.getPointId());
        vo.setPointCode(pointCodeOf(a.getPointId()));
        vo.setDeviceId(a.getDeviceId());
        vo.setDeviceCode(deviceCodeOf(a.getDeviceId()));
        vo.setAlarmReason(a.getAlarmReason());
        vo.setLevel(a.getAlarmLevel());
        vo.setStatus(a.getStatus());
        vo.setTriggeredAt(Times.iso(a.getTriggeredAt()));
        vo.setResolvedAt(Times.iso(a.getResolvedAt()));
        vo.setSnapshot(snapshot(a));
        vo.setTimeline(timeline(a.getId()));
        return vo;
    }

    /**
     * 处置警情：按动作映射状态并留痕（映射见 {@link AlarmConstants}）。
     * 已处于终态（RESOLVED / FALSE_ALARM）的警情不再接受处置。
     * <p><b>角色校验在这里做，不在控制器上</b>：允许哪个动作要等解析出请求体里的
     * {@code action} 才知道，写成 {@code @PreAuthorize} 的 SpEL 反而更绕且不好测。</p>
     */
    @Transactional
    public AlarmDetailVO act(Long id, AlarmActionRequest req, String currentUser, String role) {
        if (req == null || !notBlank(req.getAction())) {
            throw new BizException("处置动作不能为空");
        }
        String action = req.getAction().trim().toLowerCase();
        // 顺序要紧：**先认动作名，再认角色**。
        // 不存在的动作走 statusOf 的 400（这是契约既有语义，04-alarm.sh 有断言）；
        // 只有「动作合法、但这个角色不该做」才是 403。反过来写的话，
        // 一个打错的动作名会被报成「无权执行」，把 400 变成 403。
        String target = AlarmConstants.statusOf(action);
        if (!AlarmConstants.canAct(role, action)) {
            throw new BizException(403, "角色 " + (notBlank(role) ? role : "(未识别)")
                    + " 无权执行处置动作「" + action + "」（该角色可用 " + AlarmConstants.actionsOf(role) + "）");
        }
        Alarm a = require(id);
        if (AlarmConstants.isClosed(a.getStatus())) {
            throw new BizException("警情已处于终态 " + a.getStatus() + "，不能再处置");
        }

        // 写入走 CAS（前置条件 = 刚才读到的那一版状态），而不是 updateById：
        // 上面这次「读」和下面这次「写」之间没有任何互斥，两个处置人（或一个处置人 + 引擎自动解除）
        // 可以读到同一个 PENDING。后者若直接覆盖，前者的处置会被静默抹掉——
        // 时间线里两条处置记录都在，最终状态却只反映其中一条。
        // 用 status 自身当版本号：警情的每次流转都会改 status，本仓也没有 @Version 列。
        LocalDateTime now = LocalDateTime.now();
        boolean terminal = AlarmConstants.isClosed(target);
        int rows = terminal
                // 终态：置状态 + **释放未解除唯一键**。解除时间只对 RESOLVED 写，
                // FALSE_ALARM 保持既有口径（改造前就是这样，验收断言依赖它）。
                ? alarmMapper.closeAlarm(id, a.getStatus(), target, now,
                        AlarmConstants.RESOLVED.equals(target) ? now : null)
                : alarmMapper.transitionAlarm(id, a.getStatus(), target, now);
        if (rows == 0) {
            Alarm latest = alarmMapper.selectById(id);
            // 409 而不是 400：请求本身没问题，是资源状态在本次请求期间被改了。
            // 已处于终态那条路仍走 400（isClosed 检查在上面），两者语义不同，不要合并。
            throw new BizException(409, "警情已被并发处置为 "
                    + (latest == null ? "(已不存在)" : latest.getStatus())
                    + "，本次「" + action + "」未生效，请刷新后重试");
        }
        a.setStatus(target);
        if (AlarmConstants.RESOLVED.equals(target)) {
            a.setResolvedAt(now);
        }
        if (terminal) {
            a.setOpenKey(null);
        }

        AlarmAction act = new AlarmAction();
        act.setAlarmId(a.getId());
        act.setActionType(action);
        // 处置人只认登录身份，请求体不再有发言权（AlarmActionRequest 已删掉该字段）。
        // SYSTEM 只用于没有登录用户的内部调用；处置留痕是谁做的就写谁。
        // 与 MaintenanceRecordController 的 record.setOperator(currentUser.getUsername()) 同一口径。
        act.setOperator(notBlank(currentUser) ? currentUser : AlarmConstants.SYSTEM);
        act.setNote(req.getComment());
        actionMapper.insert(act);

        // 处置后广播新状态：驾驶舱的警情列表/角标要跟着变（事务中，实际推在提交后）
        broadcaster.broadcastScoped(SseBroadcaster.EVENT_ALARM,
                AlarmEvent.of(a, pointCodeOf(a.getPointId()), deviceCodeOf(a.getDeviceId())),
                () -> dataScope.projectIdsOfAlarm(a));
        return detail(id);
    }

    // ---------- 内部 ----------

    /**
     * 取警情，不存在 404、不在数据范围内 403。
     *
     * <p>加在 {@code require} 上而不是 {@code detail} 上：{@code detail} 与 {@code act}
     * 都经由本方法，其中 {@code act} 才是真正要紧的那个——只滤列表的话，
     * 一个非管理员仍然能<b>处置</b>项目 B 的警情（改状态、写处置留痕、推 SSE）。</p>
     */
    private Alarm require(Long id) {
        Alarm a = id == null ? null : alarmMapper.selectById(id);
        if (a == null) {
            throw new BizException(404, "警情不存在: " + id);
        }
        dataScope.assertAlarmVisible(a);
        return a;
    }

    /**
     * 触发快照（读时组装）。
     *
     * <p>只放**判据参数**（策略常量），不放实际观测值——观测值在时间线的 trigger 备注里，
     * 那是一段不可变的历史。设备侧的「最后上报时间」是唯一的例外：它是当前状态，
     * 用来让看的人对得上「现在这台设备怎么样了」。</p>
     */
    private Map<String, Object> snapshot(Alarm a) {
        Map<String, Object> snap = new LinkedHashMap<>();
        if (AlarmConstants.TYPE_DEVICE.equals(a.getAlarmType())) {
            // 设备告警没有测点、没有规则、也没有触发值：快照讲清「哪台设备、因为什么」
            Device d = a.getDeviceId() == null ? null : deviceMapper.selectById(a.getDeviceId());
            snap.put("deviceCode", d == null ? null : d.getCode());
            snap.put("deviceName", d == null ? null : d.getName());
            snap.put("lastReportTime", d == null ? null : Times.iso(d.getLastReportTime()));
            // 成因取自落库的 alarm_reason，不按当前状态现推——设备掉线后，
            // 一条早先因数据质量开的警情不该被解说成离线（见 V7 迁移的说明）。
            String reason = a.getAlarmReason();
            snap.put("reason", reason);
            if (AlarmConstants.REASON_DATA_QUALITY.equals(reason)) {
                snap.put("windowMinutes", DataQualityPolicy.WINDOW_MINUTES);
                snap.put("minSamples", DataQualityPolicy.MIN_SAMPLES);
                snap.put("badRatio", DataQualityPolicy.BAD_RATIO);
                snap.put("badQuality", "SUSPECT/FAULT");
            } else if (AlarmConstants.REASON_DATA_DELAY.equals(reason)) {
                snap.put("windowMinutes", DataQualityPolicy.WINDOW_MINUTES);
                snap.put("minSamples", DataQualityPolicy.MIN_SAMPLES);
                snap.put("badRatio", DataQualityPolicy.BAD_RATIO);
                snap.put("delayMinutes", DataQualityPolicy.DELAY_MINUTES);
            } else {
                // 离线（也是 V7 之前存量数据的成因）：保留原有字段名，07 套件依赖它
                snap.put("offlineMinutes", DeviceStatusPolicy.OFFLINE_MINUTES);
            }
            return snap;
        }
        AlarmRule rule = a.getRuleId() == null ? null : ruleMapper.selectById(a.getRuleId());
        snap.put(rule != null && notBlank(rule.getMetricCode()) ? rule.getMetricCode() : "value",
                a.getTriggerValue());
        if (rule != null) {
            snap.put("threshold", rule.getThresholdValue());
            snap.put("operator", rule.getOperator());
            snap.put("ruleName", rule.getName());
        }
        return snap;
    }

    private List<AlarmDetailVO.TimelineItem> timeline(Long alarmId) {
        List<AlarmAction> actions = actionMapper.selectList(new LambdaQueryWrapper<AlarmAction>()
                .eq(AlarmAction::getAlarmId, alarmId)
                .orderByAsc(AlarmAction::getCreatedAt)
                .orderByAsc(AlarmAction::getId));
        List<AlarmDetailVO.TimelineItem> items = new ArrayList<>(actions.size());
        for (AlarmAction act : actions) {
            AlarmDetailVO.TimelineItem it = new AlarmDetailVO.TimelineItem();
            it.setTime(Times.iso(act.getCreatedAt()));
            it.setAction(act.getActionType());
            it.setOperator(act.getOperator());
            it.setComment(act.getNote());
            items.add(it);
        }
        return items;
    }

    private Map<Long, String> pointCodes(Set<Long> ids) {
        Set<Long> clean = ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (clean.isEmpty()) {
            // 用 emptyMap 而不是 Map.of()：设备告警的 pointId 是 null，
            // Map.of() 在 size==0 时 get(null) 会 NPE（ImmutableCollections.MapN 显式 requireNonNull）
            return Collections.emptyMap();
        }
        return pointMapper.selectByIds(clean).stream()
                .collect(Collectors.toMap(MonitorPoint::getId, MonitorPoint::getCode, (x, y) -> x));
    }

    private Map<Long, String> deviceCodes(Set<Long> ids) {
        Set<Long> clean = ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (clean.isEmpty()) {
            return Collections.emptyMap();
        }
        return deviceMapper.selectByIds(clean).stream()
                .collect(Collectors.toMap(Device::getId, Device::getCode, (x, y) -> x));
    }

    /** 单条警情的点号；设备告警（pointId 为空）返回 null。 */
    private String pointCodeOf(Long pointId) {
        return pointId == null ? null : pointCodes(Set.of(pointId)).get(pointId);
    }

    private String deviceCodeOf(Long deviceId) {
        return deviceId == null ? null : deviceCodes(Set.of(deviceId)).get(deviceId);
    }

    /** 一次查出整页警情的处置记录，升序遍历后每个警情的最后一条即最新动作（避免 N+1）。 */
    private Map<Long, AlarmAction> lastActions(Set<Long> alarmIds) {
        Set<Long> clean = alarmIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (clean.isEmpty()) {
            return Map.of();
        }
        List<AlarmAction> all = actionMapper.selectList(new LambdaQueryWrapper<AlarmAction>()
                .in(AlarmAction::getAlarmId, clean)
                .orderByAsc(AlarmAction::getCreatedAt)
                .orderByAsc(AlarmAction::getId));
        Map<Long, AlarmAction> last = new HashMap<>();
        for (AlarmAction act : all) {
            last.put(act.getAlarmId(), act);
        }
        return last;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
