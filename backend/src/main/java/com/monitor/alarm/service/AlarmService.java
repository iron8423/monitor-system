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
@SuppressWarnings("null")
public class AlarmService {

    private final AlarmMapper alarmMapper;
    private final AlarmActionMapper actionMapper;
    private final AlarmRuleMapper ruleMapper;
    private final MonitorPointMapper pointMapper;
    private final DeviceMapper deviceMapper;
    private final SseBroadcaster broadcaster;

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

        Page<Alarm> page = alarmMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<Alarm>()
                        .eq(notBlank(level), Alarm::getAlarmLevel, level)
                        .eq(notBlank(status), Alarm::getStatus, status)
                        .eq(pointId != null, Alarm::getPointId, pointId)
                        .eq(deviceId != null, Alarm::getDeviceId, deviceId)
                        .eq(type != null, Alarm::getAlarmType, type)
                        .ge(f != null, Alarm::getTriggeredAt, f)
                        .le(t != null, Alarm::getTriggeredAt, t)
                        .orderByDesc(Alarm::getTriggeredAt)
                        .orderByDesc(Alarm::getId));

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
     */
    @Transactional
    public AlarmDetailVO act(Long id, AlarmActionRequest req, String currentUser) {
        if (req == null || !notBlank(req.getAction())) {
            throw new BizException("处置动作不能为空");
        }
        Alarm a = require(id);
        if (AlarmConstants.isClosed(a.getStatus())) {
            throw new BizException("警情已处于终态 " + a.getStatus() + "，不能再处置");
        }

        String target = AlarmConstants.statusOf(req.getAction());
        a.setStatus(target);
        if (AlarmConstants.RESOLVED.equals(target)) {
            a.setResolvedAt(LocalDateTime.now());
        }
        alarmMapper.updateById(a);

        AlarmAction act = new AlarmAction();
        act.setAlarmId(a.getId());
        act.setActionType(req.getAction().trim().toLowerCase());
        act.setOperator(notBlank(req.getOperator()) ? req.getOperator()
                : (notBlank(currentUser) ? currentUser : AlarmConstants.SYSTEM));
        act.setNote(req.getComment());
        actionMapper.insert(act);

        // 处置后广播新状态：驾驶舱的警情列表/角标要跟着变（事务中，实际推在提交后）
        broadcaster.broadcast(SseBroadcaster.EVENT_ALARM,
                AlarmEvent.of(a, pointCodeOf(a.getPointId()), deviceCodeOf(a.getDeviceId())));
        return detail(id);
    }

    // ---------- 内部 ----------

    private Alarm require(Long id) {
        Alarm a = id == null ? null : alarmMapper.selectById(id);
        if (a == null) {
            throw new BizException(404, "警情不存在: " + id);
        }
        return a;
    }

    private Map<String, Object> snapshot(Alarm a) {
        Map<String, Object> snap = new LinkedHashMap<>();
        if (AlarmConstants.TYPE_DEVICE.equals(a.getAlarmType())) {
            // 设备告警没有测点、没有规则、也没有触发值：快照讲清「哪台设备、断多久了」
            Device d = a.getDeviceId() == null ? null : deviceMapper.selectById(a.getDeviceId());
            snap.put("deviceCode", d == null ? null : d.getCode());
            snap.put("deviceName", d == null ? null : d.getName());
            snap.put("lastReportTime", d == null ? null : Times.iso(d.getLastReportTime()));
            snap.put("offlineMinutes", DeviceStatusPolicy.OFFLINE_MINUTES);
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
