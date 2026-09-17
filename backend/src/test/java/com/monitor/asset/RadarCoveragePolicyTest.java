package com.monitor.asset;

import com.monitor.asset.dto.DevicePointCalibrationRequest;
import com.monitor.asset.entity.Device;
import com.monitor.asset.entity.DevicePoint;
import com.monitor.common.exception.BizException;
import com.monitor.project.entity.MonitorPoint;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 标定口径的判据。三类：标定提交时的几何校验（{@code validateCalibration}）、
 * 标定是否仍然可用（{@code isProductionReady}）、以及**标定从哪一刻起不再成立**
 * （{@code poseChanged} / {@code pointMoved}，清单第 09 条）。
 */
class RadarCoveragePolicyTest {

    /** 固定「现在」，让有效期相关用例与运行时刻无关。 */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 17, 10, 0);

    @Test
    void acceptsTargetInsideRangeAndWrappedHorizontalFieldOfView() {
        Device device = device("350", "5", "250", "20", "12");
        DevicePointCalibrationRequest request = request("5", "10", "120", true);

        RadarCoveragePolicy.validateCalibration(device, request, NOW);
    }

    @Test
    void rejectsBlockedOrOutOfCoverageTargets() {
        Device device = device("180", "0", "100", "25", "10");

        assertThatThrownBy(() -> RadarCoveragePolicy.validateCalibration(
                device, request("180", "0", "101", true), NOW))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> RadarCoveragePolicy.validateCalibration(
                device, request("210", "0", "80", true), NOW))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> RadarCoveragePolicy.validateCalibration(
                device, request("180", "11", "80", true), NOW))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> RadarCoveragePolicy.validateCalibration(
                device, request("180", "0", "80", false), NOW))
                .isInstanceOf(BizException.class);
    }

    @Test
    void productionReadinessRequiresActiveLosAndCurrentValidity() {
        DevicePoint binding = new DevicePoint();
        binding.setCalibrationStatus("ACTIVE");
        binding.setLineOfSight(true);
        binding.setValidFrom(NOW.minusDays(1));
        binding.setValidTo(NOW.plusDays(1));
        assertThat(RadarCoveragePolicy.isProductionReady(binding, NOW)).isTrue();

        binding.setValidTo(NOW.minusSeconds(1));
        assertThat(RadarCoveragePolicy.isProductionReady(binding, NOW)).isFalse();
        binding.setValidTo(null);
        binding.setLineOfSight(false);
        assertThat(RadarCoveragePolicy.isProductionReady(binding, NOW)).isFalse();
        binding.setLineOfSight(true);
        binding.setCalibrationStatus("PENDING");
        assertThat(RadarCoveragePolicy.isProductionReady(binding, NOW)).isFalse();

        // INVALID（清单第 09 条新增的取值）必须落进「不可用」——它和 PENDING 一样要被
        // 排除，但语义不同（做过、现在不能信）。这里钉住的是「新常量没有漏出判据」。
        binding.setCalibrationStatus(RadarCoveragePolicy.INVALID);
        assertThat(RadarCoveragePolicy.isProductionReady(binding, NOW)).isFalse();
    }

    @Test
    void productionReadinessCoversValidityWindowEdges() {
        DevicePoint binding = new DevicePoint();
        binding.setCalibrationStatus("ACTIVE");
        binding.setLineOfSight(true);

        // 生效时刻尚未到（此前未覆盖的分支）
        binding.setValidFrom(NOW.plusSeconds(1));
        assertThat(RadarCoveragePolicy.isProductionReady(binding, NOW)).isFalse();
        // 恰好等于生效时刻算已生效
        binding.setValidFrom(NOW);
        assertThat(RadarCoveragePolicy.isProductionReady(binding, NOW)).isTrue();

        // 恰好等于失效时刻仍算有效（isAfter 而非 !isBefore）
        binding.setValidTo(NOW);
        assertThat(RadarCoveragePolicy.isProductionReady(binding, NOW)).isTrue();
    }

    // ---------- 清单第 09 条：几何/位姿变更 ----------

    @Test
    void poseChangedDetectsEveryGeometryField() {
        // 先钉住「原样不变 → false」这个基线，否则下面每一条 isTrue 都可能是恒真的。
        assertThat(RadarCoveragePolicy.poseChanged(pose(), pose())).isFalse();

        assertThat(RadarCoveragePolicy.poseChanged(pose(), poseWith(d -> d.setLongitude(new BigDecimal("113.5"))))).isTrue();
        assertThat(RadarCoveragePolicy.poseChanged(pose(), poseWith(d -> d.setLatitude(new BigDecimal("22.6"))))).isTrue();
        assertThat(RadarCoveragePolicy.poseChanged(pose(), poseWith(d -> d.setAltitude(new BigDecimal("88"))))).isTrue();
        assertThat(RadarCoveragePolicy.poseChanged(pose(), poseWith(d -> d.setAntennaHeightM(new BigDecimal("3.5"))))).isTrue();
        assertThat(RadarCoveragePolicy.poseChanged(pose(), poseWith(d -> d.setHeadingDegrees(new BigDecimal("233"))))).isTrue();
        assertThat(RadarCoveragePolicy.poseChanged(pose(), poseWith(d -> d.setPitchDegrees(new BigDecimal("9"))))).isTrue();
        assertThat(RadarCoveragePolicy.poseChanged(pose(), poseWith(d -> d.setDetectionRangeM(new BigDecimal("301"))))).isTrue();
        assertThat(RadarCoveragePolicy.poseChanged(pose(), poseWith(d -> d.setHalfAngleDegrees(new BigDecimal("46"))))).isTrue();
        assertThat(RadarCoveragePolicy.poseChanged(pose(), poseWith(d -> d.setVerticalHalfAngleDegrees(new BigDecimal("16"))))).isTrue();
    }

    /**
     * 上面那九条是**手抄的**，抄漏一行不会红。这条按反射把 {@code Device} 上所有
     * {@code BigDecimal} 字段数一遍：除了明确排除的，其余每一个改了都必须被判成位姿变化。
     *
     * <p>于是「给设备加了第十个几何量、却忘了加进 {@code poseChanged}」会在构建时就红，
     * 而不是等到现场有人调完天线高度发现标定没失效。</p>
     */
    @Test
    void everyBigDecimalDeviceFieldIsEitherGeometryOrExplicitlyExcluded() throws Exception {
        // 刻意的排除项，每个都要有理由（见 RadarCoveragePolicy#poseChanged 的 javadoc）：
        //   battery —— 电量，与几何无关
        Set<String> excluded = Set.of("battery");

        Set<String> seen = new java.util.TreeSet<>();
        for (java.lang.reflect.Field field : Device.class.getDeclaredFields()) {
            if (field.getType() != BigDecimal.class) continue;
            seen.add(field.getName());

            Device before = pose();
            Device after = pose();
            // 原文里没有字段的，先各给一个初值，否则「从 null 到有值」会被
            // differs 的规则③判成变化，取消掉本用例要测的东西。
            field.setAccessible(true);
            if (field.get(before) == null) field.set(before, new BigDecimal("1"));
            if (field.get(after) == null) field.set(after, new BigDecimal("1"));
            field.set(after, new BigDecimal("12345.5"));

            boolean changed = RadarCoveragePolicy.poseChanged(before, after);
            if (excluded.contains(field.getName())) {
                assertThat(changed).as("字段 %s 已声明为与几何无关", field.getName()).isFalse();
            } else {
                assertThat(changed).as("字段 %s 改了却没触发失效——要么加进 poseChanged，"
                        + "要么加进本用例的 excluded 并写清理由", field.getName()).isTrue();
            }
        }
        // 反向：排除项不该是「写错了名字的死代码」。
        assertThat(excluded).isSubsetOf(seen);
    }

    @Test
    void poseChangedIgnoresScaleOnlyDifference() {
        // 本类里最值得测的一行：equals 连 scale 一起比，compareTo 不比。
        // device.heading_degrees 是 NUMERIC(9,4)，库里读回来是 232.0000，而 Jackson
        // 反序列化 "232.0" 是 scale 1。用 equals 的话，**每一次「读出来原样存回去」
        // 都会被判成位姿变了** —— 而这个 bug 在现有套件里一条断言都不会红
        // （01-archive-auth.sh 的原样回传用的是没有绑定的临时设备），
        // 只会在生产上、在每一次保存时发作。
        Device before = pose();
        before.setHeadingDegrees(new BigDecimal("232.0000"));
        before.setLongitude(new BigDecimal("113.400000"));
        Device after = pose();
        after.setHeadingDegrees(new BigDecimal("232.0"));
        after.setLongitude(new BigDecimal("113.4"));

        assertThat(after.getHeadingDegrees()).isNotEqualTo(before.getHeadingDegrees());  // scale 不同
        assertThat(RadarCoveragePolicy.poseChanged(before, after)).isFalse();            // 但没变
    }

    @Test
    void poseChangedTreatsNullAfterAsNotSubmitted() {
        // null = 这次请求没带这个字段（updateById 会跳过），不是「清空」。
        // 若把 null 当清空，09-data-quality.sh 那三次只改名字/状态的 PUT 会全部触发失效。
        Device after = new Device();
        after.setCode("radar-001");
        after.setName("改了个名字");
        after.setBattery(new BigDecimal("88"));
        after.setStatus("ONLINE");
        after.setSerialNo("SN-NEW");

        assertThat(RadarCoveragePolicy.poseChanged(pose(), after)).isFalse();
    }

    @Test
    void poseChangedIsTrueWhenBeforeHadNoGeometry() {
        // 从「没记录」到「有值」是档案事实的变化，宁可多失效一次（重新标定即可恢复）。
        Device before = new Device();
        before.setCode("radar-001");

        assertThat(RadarCoveragePolicy.poseChanged(before, pose())).isTrue();
    }

    @Test
    void poseChangedIsFalseWithoutBothSides() {
        assertThat(RadarCoveragePolicy.poseChanged(null, pose())).isFalse();
        assertThat(RadarCoveragePolicy.poseChanged(pose(), null)).isFalse();
        assertThat(RadarCoveragePolicy.poseChanged(null, null)).isFalse();
    }

    @Test
    void pointMovedDetectsLonLatAltOnly() {
        assertThat(RadarCoveragePolicy.pointMoved(point(), point())).isFalse();
        assertThat(RadarCoveragePolicy.pointMoved(point(),
                pointWith(p -> p.setLongitude(new BigDecimal("113.5"))))).isTrue();
        assertThat(RadarCoveragePolicy.pointMoved(point(),
                pointWith(p -> p.setLatitude(new BigDecimal("22.6"))))).isTrue();
        assertThat(RadarCoveragePolicy.pointMoved(point(),
                pointWith(p -> p.setAltitude(new BigDecimal("60"))))).isTrue();

        // 换监测对象不改变「这条视线本身的几何前提」，不算移动。
        assertThat(RadarCoveragePolicy.pointMoved(point(),
                pointWith(p -> p.setObjectId(999L)))).isFalse();
        assertThat(RadarCoveragePolicy.pointMoved(point(),
                pointWith(p -> p.setCode("GC-9")))).isFalse();
    }

    @Test
    void pointMovedIsFalseWithoutBothSides() {
        assertThat(RadarCoveragePolicy.pointMoved(null, point())).isFalse();
        assertThat(RadarCoveragePolicy.pointMoved(point(), null)).isFalse();
    }

    @Test
    void calibrationValidityMustNotBeBornExpired() {
        Device device = device("180", "0", "100", "25", "10");

        // 失效时间落在过去：受理的话会生出一条出生即过期的绑定，而它同时又是 ACTIVE
        // ——isProductionReady 立刻返回 false，现场看到一条标着「有效」却收不到数的记录。
        assertThatThrownBy(() -> RadarCoveragePolicy.validateCalibration(
                device, request("180", "0", "80", true, NOW.minusSeconds(1)), NOW))
                .isInstanceOf(BizException.class);

        // 恰好等于当前时刻也算「不晚于」，同样拒绝。
        assertThatThrownBy(() -> RadarCoveragePolicy.validateCalibration(
                device, request("180", "0", "80", true, NOW), NOW))
                .isInstanceOf(BizException.class);

        // 将来：接受。
        RadarCoveragePolicy.validateCalibration(
                device, request("180", "0", "80", true, NOW.plusDays(30)), NOW);

        // 不带有效期（永久有效）：接受——这是绝大多数调用点的样子。
        RadarCoveragePolicy.validateCalibration(
                device, request("180", "0", "80", true, null), NOW);
    }

    @Test
    void rejectsValidityWindowThatEndsBeforeItStarts() {
        Device device = device("180", "0", "100", "25", "10");
        DevicePointCalibrationRequest request = request("180", "0", "80", true, NOW.plusDays(10));
        request.setValidFrom(NOW.plusDays(20));

        assertThatThrownBy(() -> RadarCoveragePolicy.validateCalibration(device, request, NOW))
                .isInstanceOf(BizException.class);
    }

    @Test
    void reportedPositionMustRemainCloseToCalibration() {
        Device device = device("350", "0", "250", "30", "15");
        DevicePoint binding = new DevicePoint();
        binding.setAzimuthDegrees(new BigDecimal("5"));
        binding.setSlantRangeM(new BigDecimal("100"));

        assertThat(RadarCoveragePolicy.matchesReportedPosition(device, binding, 15.8, 102.5)).isTrue();
        assertThat(RadarCoveragePolicy.matchesReportedPosition(device, binding, 18.1, 100)).isFalse();
        assertThat(RadarCoveragePolicy.matchesReportedPosition(device, binding, 15, 104)).isFalse();
    }

    // ---------- 夹具 ----------

    /** 一台什么几何都有的设备：做位姿基线用，任何字段被改动都应被认出来。 */
    private static Device pose() {
        Device device = new Device();
        device.setCode("radar-001");
        device.setLongitude(new BigDecimal("113.4"));
        device.setLatitude(new BigDecimal("22.5"));
        device.setAltitude(new BigDecimal("80"));
        device.setAntennaHeightM(new BigDecimal("3"));
        device.setHeadingDegrees(new BigDecimal("232"));
        device.setPitchDegrees(new BigDecimal("8"));
        device.setDetectionRangeM(new BigDecimal("300"));
        device.setHalfAngleDegrees(new BigDecimal("45"));
        device.setVerticalHalfAngleDegrees(new BigDecimal("15"));
        return device;
    }

    private static Device poseWith(java.util.function.Consumer<Device> mutation) {
        Device device = pose();
        mutation.accept(device);
        return device;
    }

    private static MonitorPoint point() {
        MonitorPoint point = new MonitorPoint();
        point.setObjectId(1L);
        point.setCode("GC-1");
        point.setLongitude(new BigDecimal("113.4"));
        point.setLatitude(new BigDecimal("22.5"));
        point.setAltitude(new BigDecimal("50"));
        return point;
    }

    private static MonitorPoint pointWith(java.util.function.Consumer<MonitorPoint> mutation) {
        MonitorPoint point = point();
        mutation.accept(point);
        return point;
    }

    private static Device device(String heading, String pitch, String range,
                                 String horizontalHalf, String verticalHalf) {
        Device device = new Device();
        device.setHeadingDegrees(new BigDecimal(heading));
        device.setPitchDegrees(new BigDecimal(pitch));
        device.setDetectionRangeM(new BigDecimal(range));
        device.setHalfAngleDegrees(new BigDecimal(horizontalHalf));
        device.setVerticalHalfAngleDegrees(new BigDecimal(verticalHalf));
        return device;
    }

    private static DevicePointCalibrationRequest request(String azimuth, String elevation,
                                                         String range, boolean lineOfSight) {
        return request(azimuth, elevation, range, lineOfSight, null);
    }

    private static DevicePointCalibrationRequest request(String azimuth, String elevation,
                                                         String range, boolean lineOfSight,
                                                         LocalDateTime validTo) {
        DevicePointCalibrationRequest request = new DevicePointCalibrationRequest();
        request.setTargetCode("TARGET-1");
        request.setAzimuthDegrees(new BigDecimal(azimuth));
        request.setElevationDegrees(new BigDecimal(elevation));
        request.setSlantRangeM(new BigDecimal(range));
        request.setLineOfSight(lineOfSight);
        request.setValidTo(validTo);
        return request;
    }
}
