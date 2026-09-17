package com.monitor.asset;

import com.monitor.asset.dto.DevicePointCalibrationRequest;
import com.monitor.asset.entity.Device;
import com.monitor.asset.entity.DevicePoint;
import com.monitor.common.exception.BizException;
import com.monitor.project.entity.MonitorPoint;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 雷达空间覆盖、标定有效性与**标定何时不再成立**的唯一业务口径。
 *
 * <p>名字里的「覆盖」不再是全部职责（{@link #poseChanged} / {@link #pointMoved} 判的是
 * 位姿变更），但不改名：4 个调用点 + 1 个测试，改名的收益是零。</p>
 */
public final class RadarCoveragePolicy {
    public static final String ACTIVE = "ACTIVE";
    public static final String PENDING = "PENDING";

    /**
     * 曾经标定过、现已作废（设备被移动/转向，或人工停用）。
     *
     * <p>与 {@link #PENDING} 是**两件事**：PENDING 是「从未标定过」，INVALID 是
     * 「标定过，但它的几何前提已经不成立了」。两者都要重新标定，但运维看它们时的
     * 结论完全不同——一个是从没做过，一个是**做过了、现在不能信**。</p>
     *
     * <p>不需要迁移加 CHECK 约束：{@code device_point.calibration_status} 是 V11 裸加的
     * {@code VARCHAR(16)}，全仓唯一的 CHECK 是 V12 给 {@code measurement.ingest_mode} 加的。</p>
     */
    public static final String INVALID = "INVALID";

    private static final BigDecimal POSITION_ANGLE_TOLERANCE_DEGREES = new BigDecimal("2.0");
    private static final BigDecimal POSITION_DISTANCE_TOLERANCE_M = new BigDecimal("2.0");
    private static final BigDecimal POSITION_DISTANCE_TOLERANCE_RATIO = new BigDecimal("0.03");

    private RadarCoveragePolicy() {
    }

    public static boolean isProductionReady(DevicePoint binding, LocalDateTime now) {
        if (binding == null || !ACTIVE.equalsIgnoreCase(binding.getCalibrationStatus())) return false;
        if (!Boolean.TRUE.equals(binding.getLineOfSight())) return false;
        if (binding.getValidFrom() != null && now.isBefore(binding.getValidFrom())) return false;
        return binding.getValidTo() == null || !now.isAfter(binding.getValidTo());
    }

    public static void validateCalibration(Device device, DevicePointCalibrationRequest request,
                                            LocalDateTime now) {
        if (!Boolean.TRUE.equals(request.getLineOfSight())) {
            throw new BizException("目标被地形或障碍物遮挡，不能激活为生产标定");
        }
        if (request.getValidFrom() != null && request.getValidTo() != null
                && !request.getValidTo().isAfter(request.getValidFrom())) {
            throw new BizException("标定失效时间必须晚于生效时间");
        }
        // 有效期不能落在过去：受理的话会**生出一条出生即过期的绑定**，而它同时又是 ACTIVE
        // ——isProductionReady 立刻返回 false，于是「刚标定完就收不到数」，
        // 现场看到的却是一条标着「有效」的标定记录。
        //
        // 这条不是假想：界面上没有 validFrom/validTo 的输入控件，而标定对话框会把
        // 行里已有的两个值带进表单（DeviceDrawer.vue），所以从界面重标定会**不知不觉
        // 沿用上一次的有效期**。挡在这里，比让它静默生出一条过期绑定好。
        if (request.getValidTo() != null && !request.getValidTo().isAfter(now)) {
            throw new BizException("标定失效时间不能早于当前时间");
        }
        BigDecimal range = device.getDetectionRangeM();
        if (range == null || request.getSlantRangeM().compareTo(range) > 0) {
            throw new BizException("目标斜距超出雷达量程");
        }
        BigDecimal horizontalHalf = device.getHalfAngleDegrees();
        if (horizontalHalf == null || angleDelta(request.getAzimuthDegrees(), device.getHeadingDegrees())
                .compareTo(horizontalHalf) > 0) {
            throw new BizException("目标方位角超出雷达水平视场");
        }
        BigDecimal verticalHalf = device.getVerticalHalfAngleDegrees();
        BigDecimal pitch = value(device.getPitchDegrees());
        if (verticalHalf == null || request.getElevationDegrees().subtract(pitch).abs()
                .compareTo(verticalHalf) > 0) {
            throw new BizException("目标俯仰角超出雷达垂直视场");
        }
    }

    /**
     * 校验报文中的雷达相对角/斜距是否仍对应这条标定目标。角度允许 2°，距离允许
     * max(2m, 3%) 的测量与安装误差；超出通常意味着转换器映射错目标或设备被移动。
     */
    public static boolean matchesReportedPosition(Device device, DevicePoint binding,
                                                   Number relativeAngleDegrees, Number distanceM) {
        if (relativeAngleDegrees == null || distanceM == null
                || binding.getAzimuthDegrees() == null || binding.getSlantRangeM() == null) {
            return true;
        }
        BigDecimal expectedRelative = signedAngleDelta(binding.getAzimuthDegrees(), device.getHeadingDegrees());
        BigDecimal actualRelative = BigDecimal.valueOf(relativeAngleDegrees.doubleValue());
        if (angleDelta(actualRelative, expectedRelative)
                .compareTo(POSITION_ANGLE_TOLERANCE_DEGREES) > 0) {
            return false;
        }
        BigDecimal actualDistance = BigDecimal.valueOf(distanceM.doubleValue());
        BigDecimal distanceTolerance = binding.getSlantRangeM()
                .multiply(POSITION_DISTANCE_TOLERANCE_RATIO)
                .max(POSITION_DISTANCE_TOLERANCE_M);
        return actualDistance.subtract(binding.getSlantRangeM()).abs().compareTo(distanceTolerance) <= 0;
    }

    /**
     * 设备位姿/覆盖是否变到「旧标定在几何上不再成立」。
     *
     * <p>清单第 09 条：设备被移动或转向之后，绑定还挂着 ACTIVE，而它的
     * {@code azimuthDegrees}/{@code slantRangeM} 是按**旧位置**量出来的——
     * 那条视线现在指向别处。触发失效的只有几何量：经纬度、高程、天线高、航向、俯仰、
     * 量程、水平/垂直半角。</p>
     *
     * <p><b>刻意不纳入的字段</b>：{@code code} 同时是接入报文的查找键
     * （{@code IngestService} 按 {@code device.code} 反查设备），改它会让历史
     * {@code measurement.device_id} 变成孤儿——那是另一条数据完整性问题，且它**不改变标定
     * 在几何上是否成立**（{@code device_point.device_id} 存的是数字主键）。
     * {@code name}/{@code battery}/{@code status}/{@code serialNo} 同理：与几何无关。</p>
     *
     * @param after 本次提交的档案；字段为 {@code null} 表示这次请求**没带**该字段
     *              （{@code updateById} 会跳过它，所以它没有被改动），不是「清空」
     */
    public static boolean poseChanged(Device before, Device after) {
        if (before == null || after == null) return false;
        return differs(before.getLongitude(), after.getLongitude())
                || differs(before.getLatitude(), after.getLatitude())
                || differs(before.getAltitude(), after.getAltitude())
                || differs(before.getAntennaHeightM(), after.getAntennaHeightM())
                || differs(before.getHeadingDegrees(), after.getHeadingDegrees())
                || differs(before.getPitchDegrees(), after.getPitchDegrees())
                || differs(before.getDetectionRangeM(), after.getDetectionRangeM())
                || differs(before.getHalfAngleDegrees(), after.getHalfAngleDegrees())
                || differs(before.getVerticalHalfAngleDegrees(), after.getVerticalHalfAngleDegrees());
    }

    /**
     * 测点是否被移动过（经纬度、高程）。
     *
     * <p>{@code objectId} 不算：换一个监测对象不会改变「这条视线本身在几何上还成不成立」，
     * 而目标的重新标定本来就要另外提交方位/斜距。</p>
     */
    public static boolean pointMoved(MonitorPoint before, MonitorPoint after) {
        if (before == null || after == null) return false;
        return differs(before.getLongitude(), after.getLongitude())
                || differs(before.getLatitude(), after.getLatitude())
                || differs(before.getAltitude(), after.getAltitude());
    }

    /**
     * 数值是否改变。三条规则都不是可有可无的：
     *
     * <p><b>① 必须用 {@code compareTo} 而不是 {@code equals}。</b>
     * {@code new BigDecimal("232.0").equals(new BigDecimal("232.000"))} 是 {@code false}
     * （{@code equals} 连 scale 一起比），而 {@code compareTo} 是 0。{@code device.heading_degrees}
     * 是 {@code NUMERIC(9,4)}，从库里读回来是 scale 4，而 Jackson 反序列化 {@code 232.0}
     * 是 scale 1——用 {@code equals} 的话，**每一次「读出来原样存回去」都会被判成位姿变了**。
     * 这是本方法里最值得单测的一行。</p>
     *
     * <p><b>② {@code after == null} 表示「本次没提交」，不是「清空」。</b>
     * 这不是图省事，而是 {@code updateById} 的真实语义（跳过 null 字段）。把 null 当清空的话，
     * 每一次只改名字/电量的 PUT 都会把所有标定打失效——而 {@code 09-data-quality.sh}
     * 恰恰有三次这样的 PUT。</p>
     *
     * <p><b>③ {@code before == null} 且 {@code after != null} 算改变</b>：
     * 从「没记录」到「有值」是档案事实的变化，宁可多失效一次（重新标定即可恢复）。</p>
     */
    private static boolean differs(BigDecimal before, BigDecimal after) {
        if (after == null) return false;
        return before == null || after.compareTo(before) != 0;
    }

    private static BigDecimal angleDelta(BigDecimal angle, BigDecimal centre) {
        return signedAngleDelta(angle, centre).abs();
    }

    private static BigDecimal signedAngleDelta(BigDecimal angle, BigDecimal centre) {
        double raw = (angle.doubleValue() - value(centre).doubleValue() + 540.0) % 360.0 - 180.0;
        return BigDecimal.valueOf(raw);
    }

    private static BigDecimal value(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
