package com.monitor.asset.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.monitor.asset.RadarCoveragePolicy;
import com.monitor.asset.entity.Device;
import com.monitor.asset.entity.DevicePoint;
import com.monitor.asset.mapper.DeviceMapper;
import com.monitor.asset.mapper.DevicePointMapper;
import com.monitor.common.exception.BizException;
import com.monitor.project.entity.MonitorPoint;
import com.monitor.project.mapper.MonitorPointMapper;
import com.monitor.support.MybatisPlusLambdaCache;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「位姿一变，旧标定不再自称有效」（清单第 09 条）的接线判据。
 *
 * <p>分工：判定逻辑（{@code poseChanged} / {@code pointMoved}）由
 * {@code RadarCoveragePolicyTest} 拥有，注解与响应形状由 {@code 13-calibration.sh} 拥有。
 * 这里测的是中间那一层——**服务有没有在正确的时候调正确的 mapper，并且带对参数**。</p>
 *
 * <p>刻意不引入 MockMvc：全仓一处都没有，而 {@code @WithMockUser} 只能证明注解可读、
 * 证明不了端点被保护。真正有牙齿的是验收套件里那条「MAINTAINER PUT → 403」。</p>
 */
@ExtendWith(MockitoExtension.class)
class CalibrationServiceTest {

    @Mock private DeviceMapper deviceMapper;
    @Mock private DevicePointMapper devicePointMapper;
    @Mock private MonitorPointMapper monitorPointMapper;

    private CalibrationService service;

    @BeforeAll
    static void warmLambdaCaches() {
        // invalidateCalibration 会构造 LambdaQueryWrapper，列名解析是急切的。
        MybatisPlusLambdaCache.warm(DevicePoint.class);
    }

    @BeforeEach
    void setUp() {
        service = new CalibrationService(deviceMapper, devicePointMapper, monitorPointMapper);
    }

    // ---------- updateDevice ----------

    @Test
    void poseChangeInvalidatesActiveCalibrations() {
        Device before = device();
        Device after = device();
        after.setHeadingDegrees(new BigDecimal("310"));
        when(deviceMapper.selectById(7L)).thenReturn(before, after);
        when(devicePointMapper.invalidateActiveOfDevice(eq(7L), eq(RadarCoveragePolicy.INVALID),
                eq(CalibrationService.REASON_DEVICE_POSE_CHANGED), any(), eq("admin")))
                .thenReturn(3);

        CalibrationService.DeviceUpdateOutcome outcome = service.updateDevice(7L, after, "admin");

        assertThat(outcome.invalidated()).isEqualTo(3);
        assertThat(outcome.device()).isSameAs(after);
        verify(devicePointMapper).invalidateActiveOfDevice(eq(7L), eq("INVALID"),
                eq("DEVICE_POSE_CHANGED"), any(LocalDateTime.class), eq("admin"));
    }

    @Test
    void nonGeometryUpdateLeavesCalibrationsAlone() {
        // 只改名字/电量/状态：这是 09-data-quality.sh 里那三次 PUT 的样子，
        // 也是用户在界面上最常做的编辑。误触发的话，运维会看到一批莫名其妙的「已失效」。
        Device before = device();
        Device after = new Device();
        after.setName("改了个名字");
        after.setBattery(new BigDecimal("88"));
        after.setStatus("ONLINE");
        when(deviceMapper.selectById(7L)).thenReturn(before, before);

        CalibrationService.DeviceUpdateOutcome outcome = service.updateDevice(7L, after, "admin");

        assertThat(outcome.invalidated()).isZero();
        verify(devicePointMapper, never()).invalidateActiveOfDevice(anyLong(), any(), any(), any(), any());
        verify(deviceMapper).updateById(after);
    }

    @Test
    void roundTrippingTheArchiveDoesNotInvalidate() {
        // GET 回来的档案原样 PUT 回去。库里 heading 是 NUMERIC(9,4) 的 232.0000，
        // 而 JSON 里是 232.0——用 equals 比就会在这里误报。这条是本类里最要紧的一条：
        // 生产上每一次「打开抽屉、点保存」都会走这条路。
        Device before = device();
        before.setHeadingDegrees(new BigDecimal("232.0000"));
        Device after = device();
        after.setHeadingDegrees(new BigDecimal("232.0"));
        when(deviceMapper.selectById(7L)).thenReturn(before, before);

        assertThat(service.updateDevice(7L, after, "admin").invalidated()).isZero();
        verify(devicePointMapper, never()).invalidateActiveOfDevice(anyLong(), any(), any(), any(), any());
    }

    @Test
    void missingDeviceKeepsTheBaseBehaviour() {
        // 不存在时保持基类今天的行为：写一次、返回 null，响应是 200 + data:null。
        // 不在这里「顺手改成 404」——那是一次没被要求的语义变更，且 01 套件没断言过。
        when(deviceMapper.selectById(404L)).thenReturn(null);

        CalibrationService.DeviceUpdateOutcome outcome = service.updateDevice(404L, device(), "admin");

        assertThat(outcome.device()).isNull();
        assertThat(outcome.invalidated()).isZero();
        verify(devicePointMapper, never()).invalidateActiveOfDevice(anyLong(), any(), any(), any(), any());
    }

    @Test
    void deviceUpdateAlwaysWritesTheIdFromThePath() {
        // body 里的 id 不可信：以路径为准，否则 PUT /devices/7 带 {"id":9} 会改到别的设备。
        Device after = new Device();
        after.setId(9L);
        after.setName("x");
        when(deviceMapper.selectById(7L)).thenReturn(device(), device());

        service.updateDevice(7L, after, "admin");

        assertThat(after.getId()).isEqualTo(7L);
    }

    @Test
    void invalidationHappensBeforeTheArchiveIsWritten() {
        // 顺序是承重的（见 CalibrationService#updateDevice 的 javadoc）：万一将来有人拿掉
        // @Transactional，中途失败留下的是「过度失效」（显式、重新标定即可恢复），
        // 而不是「档案已挪、标定还 ACTIVE」那个静默的失效不足。
        Device before = device();
        Device after = device();
        after.setHeadingDegrees(new BigDecimal("310"));
        when(deviceMapper.selectById(7L)).thenReturn(before, after);
        when(devicePointMapper.invalidateActiveOfDevice(anyLong(), any(), any(), any(), any()))
                .thenReturn(1);

        service.updateDevice(7L, after, "admin");

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(devicePointMapper, deviceMapper);
        order.verify(devicePointMapper).invalidateActiveOfDevice(anyLong(), any(), any(), any(), any());
        order.verify(deviceMapper).updateById(after);
    }

    // ---------- updatePoint ----------

    @Test
    void pointMoveInvalidatesCalibrationsOfEveryRadarWatchingIt() {
        MonitorPoint before = point();
        MonitorPoint after = point();
        after.setLongitude(new BigDecimal("113.5"));
        when(monitorPointMapper.selectById(3L)).thenReturn(before, after);
        when(devicePointMapper.invalidateActiveOfPoint(eq(3L), eq(RadarCoveragePolicy.INVALID),
                eq(CalibrationService.REASON_POINT_MOVED), any(), eq("maintainer")))
                .thenReturn(2);

        CalibrationService.PointUpdateOutcome outcome = service.updatePoint(3L, after, "maintainer");

        assertThat(outcome.invalidated()).isEqualTo(2);
        // 按 point_id 失效（不是 device_id）：同一个测点可能被多台雷达观测，只失效一台是漏的。
        verify(devicePointMapper).invalidateActiveOfPoint(eq(3L), any(), any(), any(), any());
        verify(devicePointMapper, never()).invalidateActiveOfDevice(anyLong(), any(), any(), any(), any());
    }

    @Test
    void renamingAPointLeavesCalibrationsAlone() {
        MonitorPoint before = point();
        MonitorPoint after = new MonitorPoint();
        after.setName("改名");
        after.setObjectId(9L);
        when(monitorPointMapper.selectById(3L)).thenReturn(before, before);

        assertThat(service.updatePoint(3L, after, "admin").invalidated()).isZero();
        verify(devicePointMapper, never()).invalidateActiveOfPoint(anyLong(), any(), any(), any(), any());
    }

    // ---------- invalidateCalibration ----------

    @Test
    void manualInvalidationUsesTheDefaultReasonAndReturnsTheFreshRow() {
        DevicePoint binding = binding(55L);
        DevicePoint afterInvalidation = binding(55L);
        afterInvalidation.setCalibrationStatus(RadarCoveragePolicy.INVALID);
        when(devicePointMapper.selectOne(ArgumentMatchers.<Wrapper<DevicePoint>>any())).thenReturn(binding);
        when(devicePointMapper.invalidateOne(eq(7L), eq(3L), eq(RadarCoveragePolicy.INVALID),
                eq(CalibrationService.REASON_MANUAL), any(), eq("admin"))).thenReturn(1);
        when(devicePointMapper.selectById(55L)).thenReturn(afterInvalidation);

        DevicePoint result = service.invalidateCalibration(7L, 3L, null, "admin");

        assertThat(result.getCalibrationStatus()).isEqualTo("INVALID");
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(devicePointMapper).invalidateOne(anyLong(), anyLong(), any(), reason.capture(), any(), any());
        assertThat(reason.getValue()).isEqualTo("MANUAL");
    }

    @Test
    void manualInvalidationIsIdempotent() {
        // 已经 INVALID 再调：200 且**不改写**痕迹。否则「谁在什么时候停用的」
        // 会变成最后一次点按钮的人和时间，这一列作为证据就废了。
        DevicePoint already = binding(55L);
        already.setCalibrationStatus(RadarCoveragePolicy.INVALID);
        already.setInvalidatedAt(LocalDateTime.of(2026, 9, 1, 8, 0));
        already.setInvalidatedBy("someone-else");
        when(devicePointMapper.selectOne(ArgumentMatchers.<Wrapper<DevicePoint>>any())).thenReturn(already);
        when(devicePointMapper.invalidateOne(anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(0);

        DevicePoint result = service.invalidateCalibration(7L, 3L, "maintenance", "admin");

        assertThat(result).isSameAs(already);
        assertThat(result.getInvalidatedBy()).isEqualTo("someone-else");
        // 关键：没有再去读一次库把它「刷新」成自己的痕迹。
        verify(devicePointMapper, never()).selectById(anyLong());
    }

    @Test
    void manualInvalidationRejectsUnknownReason() {
        // 白名单而不是自由文本：这一列会被前端当枚举渲染、被运维 grep。
        assertThatThrownBy(() -> service.invalidateCalibration(7L, 3L, "随便写的理由", "admin"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不支持的人工失效原因");
        verify(devicePointMapper, never()).invalidateOne(anyLong(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void manualInvalidationNormalizesTheReasonCasing() {
        DevicePoint binding = binding(55L);
        when(devicePointMapper.selectOne(ArgumentMatchers.<Wrapper<DevicePoint>>any())).thenReturn(binding);
        when(devicePointMapper.invalidateOne(anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(0);

        service.invalidateCalibration(7L, 3L, "  maintenance  ", "admin");

        verify(devicePointMapper).invalidateOne(eq(7L), eq(3L), any(), eq("MAINTENANCE"), any(), any());
    }

    @Test
    void manualInvalidationRejectsUnboundPair() {
        when(devicePointMapper.selectOne(ArgumentMatchers.<Wrapper<DevicePoint>>any())).thenReturn(null);

        assertThatThrownBy(() -> service.invalidateCalibration(7L, 3L, null, "admin"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("尚未绑定");
    }

    @Test
    void manualInvalidationAcceptsEveryWhitelistedReason() {
        // 白名单里的值一个都不能因为拼错而失效——它们会出现在运维的 curl 里。
        for (String reason : new String[]{"MANUAL", "DEVICE_RELOCATED", "TARGET_REMOVED",
                "MAINTENANCE", "SUSPECTED_DRIFT"}) {
            DevicePoint binding = binding(55L);
            org.mockito.Mockito.lenient().when(devicePointMapper.selectOne(ArgumentMatchers.<Wrapper<DevicePoint>>any()))
                    .thenReturn(binding);
            org.mockito.Mockito.lenient()
                    .when(devicePointMapper.invalidateOne(anyLong(), anyLong(), any(), any(), any(), any()))
                    .thenReturn(0);

            assertThat(service.invalidateCalibration(7L, 3L, reason, "admin"))
                    .as("原因码 %s 应在白名单内", reason)
                    .isNotNull();
        }
    }

    // ---------- 夹具 ----------

    private static Device device() {
        Device device = new Device();
        device.setId(7L);
        device.setCode("radar-001");
        device.setLongitude(new BigDecimal("113.4"));
        device.setLatitude(new BigDecimal("22.5"));
        device.setAltitude(new BigDecimal("80"));
        device.setHeadingDegrees(new BigDecimal("232"));
        device.setPitchDegrees(new BigDecimal("8"));
        device.setDetectionRangeM(new BigDecimal("300"));
        device.setHalfAngleDegrees(new BigDecimal("45"));
        device.setVerticalHalfAngleDegrees(new BigDecimal("15"));
        return device;
    }

    private static MonitorPoint point() {
        MonitorPoint point = new MonitorPoint();
        point.setId(3L);
        point.setObjectId(1L);
        point.setCode("GC-1");
        point.setLongitude(new BigDecimal("113.4"));
        point.setLatitude(new BigDecimal("22.5"));
        point.setAltitude(new BigDecimal("50"));
        return point;
    }

    private static DevicePoint binding(Long id) {
        DevicePoint binding = new DevicePoint();
        binding.setId(id);
        binding.setDeviceId(7L);
        binding.setPointId(3L);
        binding.setCalibrationStatus(RadarCoveragePolicy.ACTIVE);
        binding.setLineOfSight(true);
        return binding;
    }
}
