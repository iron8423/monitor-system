package com.monitor.asset;

import com.monitor.asset.entity.Device;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 设备状态判定口径（唯一实现）。
 *
 * <p>《B侧接口契约_M0》§5 明确「在线判定归 A，B 不得重复实现」——故把规则集中在此，
 * `DeviceController`（A 的设备状态接口）与项目概览的在线设备计数共用，避免两处阈值漂移。</p>
 */
public final class DeviceStatusPolicy {

    /** 超过该时长（分钟）未上报视为离线。 */
    public static final int OFFLINE_MINUTES = 5;

    /** 低于该电量视为低电量。 */
    public static final BigDecimal LOW_BATTERY_THRESHOLD = new BigDecimal("20");

    private DeviceStatusPolicy() {
    }

    /** 最近上报时间在 {@link #OFFLINE_MINUTES} 窗口内即为在线。 */
    public static boolean isOnline(LocalDateTime lastReportTime) {
        return lastReportTime != null
                && lastReportTime.isAfter(LocalDateTime.now().minusMinutes(OFFLINE_MINUTES));
    }

    public static boolean isLowBattery(BigDecimal battery) {
        return battery != null && battery.compareTo(LOW_BATTERY_THRESHOLD) < 0;
    }

    /** 状态串：档案标记 FAULT 优先，其次按上报时间推在线/离线。 */
    public static String statusOf(Device device) {
        if (device == null) {
            return null;
        }
        if ("FAULT".equals(device.getStatus())) {
            return "FAULT";
        }
        return isOnline(device.getLastReportTime()) ? "ONLINE" : "OFFLINE";
    }
}
