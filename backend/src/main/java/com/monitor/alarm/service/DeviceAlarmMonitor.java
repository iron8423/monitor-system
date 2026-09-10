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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 设备离线告警（验收第 5 条：断连模拟器 -&gt; 设备标为离线 + 生成设备告警，恢复后自动解除）。
 *
 * <p>为什么需要它：{@code DeviceStatusPolicy} 是**读时计算**（拉），
 * {@code GET /devices/{id}/status} 能正确返回 OFFLINE，但「离线」从来不会变成一条可处置、
 * 可留痕、能进警情列表的记录——不去查状态就什么都不会发生。这里把「推」补上：
 * 定时扫描，掉线的开警情，回来的自动解除。</p>
 *
 * <p>三条设计取舍：</p>
 * <ol>
 *   <li><b>判据复用</b> {@link DeviceStatusPolicy#OFFLINE_MINUTES}，与本文件之外的在线判定同一口径，
 *       避免「状态页显示在线、告警却说离线」这种两套阈值漂移；</li>
 *   <li><b>从不报数的设备不告警</b>（{@code last_report_time} 为空）——刚建档还没上线的设备
 *       报「离线」是噪音，不是事件；</li>
 *   <li><b>等级取最低的 {@code notice}</b>——离线是可恢复的运行状态，不是形变险情，
 *       不该跟真正的超限告警抢注意力。</li>
 * </ol>
 *
 * <p>每个设备一次事务，单个设备出错不影响其余（异常只记日志）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressWarnings("null")
public class DeviceAlarmMonitor {

    /** 设备离线告警等级（D5 最低档）。 */
    static final String OFFLINE_LEVEL = "notice";

    private final DeviceMapper deviceMapper;
    private final AlarmMapper alarmMapper;
    private final AlarmActionMapper actionMapper;
    private final SseBroadcaster broadcaster;
    private final TransactionTemplate txTemplate;

    /** 扫描间隔与首扫延迟同一个值：配置项只有一个，见 application.yml 的 monitor.device-offline.sweep-ms。 */
    @Scheduled(fixedDelayString = "${monitor.device-offline.sweep-ms:10000}",
            initialDelayString = "${monitor.device-offline.sweep-ms:10000}")
    public void sweep() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(DeviceStatusPolicy.OFFLINE_MINUTES);
        List<Device> devices = deviceMapper.selectList(null);
        if (devices.isEmpty()) {
            return;
        }
        for (Device d : devices) {
            try {
                txTemplate.executeWithoutResult(status -> reconcile(d, cutoff));
            } catch (Exception e) {
                log.error("设备离线扫描失败 deviceId={} code={}", d.getId(), d.getCode(), e);
            }
        }
    }

    /** 单个设备：掉线且无未解除告警 -> 开；已恢复且有未解除告警 -> 解。其余不动（天然防抖）。 */
    private void reconcile(Device d, LocalDateTime cutoff) {
        Alarm open = openAlarmOf(d.getId());
        boolean offline = isOffline(d, cutoff);
        if (offline && open == null) {
            raise(d);
        } else if (!offline && open != null) {
            clear(open, d);
        }
    }

    /**
     * 与 {@link DeviceStatusPolicy#statusOf} 同一口径：{@code FAULT} 是人在档案里显式标注的状态，
     * 优先于「按最后上报时间推出来的离线」。设备被标故障时，离线告警应当解除而不是并存。
     */
    private boolean isOffline(Device d, LocalDateTime cutoff) {
        if ("FAULT".equals(d.getStatus())) {
            return false;
        }
        return d.getLastReportTime() != null && d.getLastReportTime().isBefore(cutoff);
    }

    private Alarm openAlarmOf(Long deviceId) {
        return alarmMapper.selectOne(new LambdaQueryWrapper<Alarm>()
                .eq(Alarm::getAlarmType, AlarmConstants.TYPE_DEVICE)
                .eq(Alarm::getDeviceId, deviceId)
                .notIn(Alarm::getStatus, AlarmConstants.CLOSED)
                .orderByDesc(Alarm::getTriggeredAt)
                .orderByDesc(Alarm::getId)
                .last("LIMIT 1"));
    }

    private void raise(Device d) {
        Alarm a = new Alarm();
        a.setAlarmType(AlarmConstants.TYPE_DEVICE);
        a.setDeviceId(d.getId());
        a.setAlarmLevel(OFFLINE_LEVEL);
        a.setStatus(AlarmConstants.PENDING);
        a.setTriggeredAt(LocalDateTime.now());
        alarmMapper.insert(a);

        actionMapper.insert(action(a.getId(), AlarmConstants.ACTION_TRIGGER, String.format(
                "设备 %s（%s）已超过 %d 分钟未上报数据，判定为离线",
                d.getCode(), d.getName(), DeviceStatusPolicy.OFFLINE_MINUTES)));
        broadcaster.broadcast(SseBroadcaster.EVENT_ALARM, AlarmEvent.of(a, null, d.getCode()));
        log.info("设备离线告警 alarmId={} deviceId={} code={} lastReportTime={}",
                a.getId(), d.getId(), d.getCode(), d.getLastReportTime());
    }

    private void clear(Alarm a, Device d) {
        a.setStatus(AlarmConstants.RESOLVED);
        a.setResolvedAt(LocalDateTime.now());
        alarmMapper.updateById(a);

        actionMapper.insert(action(a.getId(), AlarmConstants.ACTION_RECOVER,
                String.format("设备 %s 已恢复上报，系统自动解除", d.getCode())));
        broadcaster.broadcast(SseBroadcaster.EVENT_ALARM, AlarmEvent.of(a, null, d.getCode()));
        log.info("设备离线告警解除 alarmId={} deviceId={} code={}", a.getId(), d.getId(), d.getCode());
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
