package com.monitor.alarm.dto;

import com.monitor.alarm.entity.Alarm;
import com.monitor.common.util.Times;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSE {@code alarm} 事件载荷（《B侧接口契约_M0》§6）。
 *
 * <p>契约示例为 {@code {id, pointCode, level, status, triggeredAt}}；这里额外带 {@code pointId}，
 * 与 D10「响应同时给数值 id 与字符串 code」一致（其余列表/详情接口同样两者都给）。</p>
 *
 * <p>设备告警（{@code alarmType = DEVICE}）没有测点：{@code pointId/pointCode} 为空，
 * 改带 {@code deviceId/deviceCode}，前端按 {@code alarmType} 决定渲染哪一个。</p>
 */
public final class AlarmEvent {

    private AlarmEvent() {
    }

    /** 测点警情。 */
    public static Map<String, Object> of(Alarm alarm, String pointCode) {
        return of(alarm, pointCode, null);
    }

    /** 通用：设备告警传 {@code pointCode = null} + 设备码。 */
    public static Map<String, Object> of(Alarm alarm, String pointCode, String deviceCode) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", alarm.getId());
        event.put("alarmType", alarm.getAlarmType());
        event.put("pointId", alarm.getPointId());
        event.put("pointCode", pointCode);
        event.put("deviceId", alarm.getDeviceId());
        event.put("deviceCode", deviceCode);
        event.put("level", alarm.getAlarmLevel());
        event.put("status", alarm.getStatus());
        event.put("triggeredAt", Times.iso(alarm.getTriggeredAt()));
        return event;
    }
}
