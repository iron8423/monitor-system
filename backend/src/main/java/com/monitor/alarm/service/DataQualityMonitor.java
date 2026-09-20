package com.monitor.alarm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.monitor.alarm.AlarmConstants;
import com.monitor.alarm.dto.AlarmEvent;
import com.monitor.alarm.entity.Alarm;
import com.monitor.alarm.entity.AlarmAction;
import com.monitor.alarm.mapper.AlarmActionMapper;
import com.monitor.alarm.mapper.AlarmMapper;
import com.monitor.asset.DeviceStatusPolicy;
import com.monitor.asset.entity.Device;
import com.monitor.asset.mapper.DeviceMapper;
import com.monitor.common.sse.SseBroadcaster;
import com.monitor.telemetry.DataQualityPolicy;
import com.monitor.telemetry.entity.Measurement;
import com.monitor.telemetry.mapper.MeasurementMapper;
import com.monitor.scope.service.DataScopeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据可信度告警（验收第 5 条的扩展面：数据质量异常 / 数据延迟上报）。
 *
 * <p>与 {@link DeviceAlarmMonitor} 同构：定时扫描、每台设备一次事务、异常只记日志不影响其余。
 * 判据见 {@link DataQualityPolicy}，成因写 {@code alarm_reason}。</p>
 *
 * <p>四条设计取舍：</p>
 * <ol>
 *   <li><b>只在设备「在报数」时判</b>（{@code lastReportTime} 新鲜）。设备已经掉线是
 *       {@link DeviceAlarmMonitor} 的事，这里再开一条只会让同一件事显示两遍；而且离线设备
 *       自然收不到新数据，判据必然落空，判它是纯噪音。标记 {@code FAULT} 的设备同理跳过——
 *       那是人在档案里显式声明的状态，数据不可信是它的已知后果。</li>
 *   <li><b>设备不新鲜时「不动」已有警情</b>——既不新开也不解除。这是关键的一条：
 *       设备掉线后窗口会空掉，若按「窗口不再满足判据」就解除，就会把一条**从未恢复**的
 *       数据质量警情悄悄销掉。警情只在设备**在报数且数据确实好了**的时候才解除。</li>
 *   <li><b>两个成因各自独立成警</b>（按 {@code deviceId + alarmReason} 找未解除警情）。
 *       一台设备可以同时「数据不可信」与「已经掉线」，两条都该留着——这与
 *       {@link DeviceAlarmMonitor} 之前只按 {@code alarmType} 找的写法不兼容，
 *       故那个方法一并加了成因条件（见 {@code V7__alarm_reason.sql} 的说明）。</li>
 *   <li><b>等级取最低的 {@code notice}</b>，与离线告警一致：数据可信度是运行状态，
 *       不是形变险情，不该跟真正的超限告警抢注意力。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataQualityMonitor {

    /** 数据可信度告警等级（D5 最低档），与离线告警同档。 */
    static final String LEVEL = "notice";

    private final DeviceMapper deviceMapper;
    private final MeasurementMapper measurementMapper;
    private final AlarmMapper alarmMapper;
    private final AlarmActionMapper actionMapper;
    private final SseBroadcaster broadcaster;
    private final DataScopeService dataScope;
    private final TransactionTemplate txTemplate;

    /** 扫描间隔与首扫延迟同一个值，理由同 DeviceAlarmMonitor。 */
    @Scheduled(fixedDelayString = "${monitor.data-quality.sweep-ms:10000}",
            initialDelayString = "${monitor.data-quality.sweep-ms:10000}")
    public void sweep() {
        LocalDateTime now = LocalDateTime.now();
        List<Device> devices = deviceMapper.selectList(null);
        if (devices.isEmpty()) {
            return;
        }
        for (Device d : devices) {
            try {
                txTemplate.executeWithoutResult(status -> reconcileDevice(d, now));
            } catch (Exception e) {
                log.error("数据可信度扫描失败 deviceId={} code={}", d.getId(), d.getCode(), e);
            }
        }
    }

    private void reconcileDevice(Device d, LocalDateTime now) {
        List<Measurement> window = measurementMapper.recentOfDevice(
                d.getCode(), DataQualityPolicy.windowStart(now));
        boolean judgeable = judgeable(d);
        reconcile(d, window, now, AlarmConstants.REASON_DATA_QUALITY, judgeable);
        reconcile(d, window, now, AlarmConstants.REASON_DATA_DELAY, judgeable);
    }

    /**
     * 这台设备现在能不能判数据可信度：在报数、且没被档案显式标成故障。
     * 判不了时两个成因都**保持原样**（见类注释第 2 条）。
     */
    private boolean judgeable(Device d) {
        if ("FAULT".equals(d.getStatus())) {
            return false;
        }
        return DeviceStatusPolicy.isOnline(d.getLastReportTime());
    }

    /** 单个成因：判据成立且无未解除警情 -> 开；判据不成立且有 -> 解；判不了 -> 不动。 */
    private void reconcile(Device d, List<Measurement> window, LocalDateTime now,
                           String reason, boolean judgeable) {
        if (!judgeable) {
            return;
        }
        boolean bad = AlarmConstants.REASON_DATA_QUALITY.equals(reason)
                ? DataQualityPolicy.qualityBad(window)
                : DataQualityPolicy.delayBad(window, now);
        Alarm open = openAlarmOf(d.getId(), reason);
        if (bad && open == null) {
            raise(d, reason, window, now);
        } else if (!bad && open != null) {
            clear(open, d, reason);
        }
    }

    /**
     * 找该设备**该成因**下未解除的警情。
     * <p>成因条件不可省：只按 {@code alarmType=DEVICE} 找的话，一条数据质量警情会让离线扫描
     * 以为「已经告过警了」而不再开离线警情，反过来也一样——两边互相掩盖。</p>
     */
    private Alarm openAlarmOf(Long deviceId, String reason) {
        return alarmMapper.selectOne(new LambdaQueryWrapper<Alarm>()
                .eq(Alarm::getAlarmType, AlarmConstants.TYPE_DEVICE)
                .eq(Alarm::getAlarmReason, reason)
                .eq(Alarm::getDeviceId, deviceId)
                .notIn(Alarm::getStatus, AlarmConstants.CLOSED)
                .orderByDesc(Alarm::getTriggeredAt)
                .orderByDesc(Alarm::getId)
                .last("LIMIT 1"));
    }

    private void raise(Device d, String reason, List<Measurement> window, LocalDateTime now) {
        Alarm a = new Alarm();
        a.setAlarmType(AlarmConstants.TYPE_DEVICE);
        a.setDeviceId(d.getId());
        a.setAlarmReason(reason);
        // 未解除唯一键（V14）：'D:<deviceId>:<reason>'。成因必须进键——
        // 一台设备可以同时欠着「数据不可信」与「已经掉线」，用只带 deviceId 的键会让
        // 后开的那条撞上唯一索引，两个成因里永远只有一个能存在。
        a.setOpenKey(AlarmConstants.openKeyOfDevice(d.getId(), reason));
        a.setAlarmLevel(LEVEL);
        a.setStatus(AlarmConstants.PENDING);
        a.setTriggeredAt(now);
        alarmMapper.insert(a);

        // 留痕写**实际观测到的数**（判定用的是哪批样本、坏了几条），
        // 策略参数走 snapshot。两者分工与离线告警一致，且时间线是不可变历史。
        actionMapper.insert(action(a.getId(), AlarmConstants.ACTION_TRIGGER, raiseNote(d, reason, window, now)));
        broadcaster.broadcastScoped(SseBroadcaster.EVENT_ALARM, AlarmEvent.of(a, null, d.getCode()),
                () -> dataScope.projectIdsOfAlarm(a));
        log.info("数据可信度告警 alarmId={} deviceId={} code={} reason={} samples={}",
                a.getId(), d.getId(), d.getCode(), reason, window.size());
    }

    private String raiseNote(Device d, String reason, List<Measurement> window, LocalDateTime now) {
        if (AlarmConstants.REASON_DATA_QUALITY.equals(reason)) {
            long bad = window.stream().filter(DataQualityPolicy::isBadQuality).count();
            return String.format(
                    "设备 %s（%s）最近 %d 分钟上报的 %d 条数据中有 %d 条质量异常（SUSPECT/FAULT），"
                            + "占比超过 %d%%，判定数据不可信",
                    d.getCode(), d.getName(), DataQualityPolicy.WINDOW_MINUTES, window.size(), bad,
                    (int) Math.round(DataQualityPolicy.BAD_RATIO * 100));
        }
        long late = window.stream().filter(m -> DataQualityPolicy.isDelayed(m, now)).count();
        return String.format(
                "设备 %s（%s）最近 %d 分钟上报的 %d 条数据中有 %d 条接收时间落后采集时间超过 %d 分钟，"
                        + "占比超过 %d%%，判定为延迟上报",
                d.getCode(), d.getName(), DataQualityPolicy.WINDOW_MINUTES, window.size(), late,
                DataQualityPolicy.DELAY_MINUTES, (int) Math.round(DataQualityPolicy.BAD_RATIO * 100));
    }

    private void clear(Alarm a, Device d, String reason) {
        LocalDateTime now = LocalDateTime.now();
        // 关闭必须走 CAS：① 必须把 open_key 置回 NULL（updateById 跳过 null 字段，写不出去，
        // 那条警情会永久占住键位，该设备该成因**从此再也开不出警情**）；
        // ② 前置条件是刚读到的状态——人工处置（AlarmService.act）与本扫描没有互斥，
        // 覆盖写会把处置人的结果抹掉，时间线里却留着两条。
        int rows = alarmMapper.closeAlarm(a.getId(), a.getStatus(), AlarmConstants.RESOLVED, now, now);
        if (rows == 0) {
            log.info("数据可信度告警解除跳过（状态已被改变）alarmId={} 期望 {}", a.getId(), a.getStatus());
            return;
        }
        a.setStatus(AlarmConstants.RESOLVED);
        a.setResolvedAt(now);
        a.setOpenKey(null);

        actionMapper.insert(action(a.getId(), AlarmConstants.ACTION_RECOVER, String.format(
                "设备 %s 最近 %d 分钟的数据已恢复正常，系统自动解除",
                d.getCode(), DataQualityPolicy.WINDOW_MINUTES)));
        broadcaster.broadcastScoped(SseBroadcaster.EVENT_ALARM, AlarmEvent.of(a, null, d.getCode()),
                () -> dataScope.projectIdsOfAlarm(a));
        log.info("数据可信度告警解除 alarmId={} deviceId={} code={} reason={}",
                a.getId(), d.getId(), d.getCode(), reason);
    }

    private AlarmAction action(Long alarmId, String type, String note) {
        AlarmAction act = new AlarmAction();
        act.setAlarmId(alarmId);
        act.setActionType(type);
        act.setOperator(AlarmConstants.SYSTEM);
        act.setNote(note);
        return act;
    }
}
