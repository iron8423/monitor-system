package com.monitor.twin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.asset.entity.Device;
import com.monitor.asset.entity.DevicePoint;
import com.monitor.project.entity.DigitalTwinScene;
import com.monitor.project.entity.MonitorObject;
import com.monitor.project.entity.MonitorPoint;
import com.monitor.project.entity.Scene;
import com.monitor.project.mapper.DigitalTwinSceneMapper;
import com.monitor.project.mapper.MonitorObjectMapper;
import com.monitor.project.mapper.MonitorPointMapper;
import com.monitor.project.mapper.SceneMapper;
import com.monitor.support.MybatisPlusLambdaCache;
import com.monitor.twin.dto.CalibrationPreviewRequest;
import com.monitor.twin.dto.CalibrationPreviewVO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 标定试算与「声称通视但地形判定遮挡」的拒收（P1-10）。
 *
 * <p>用的是真实高程场（classpath 里那份山地资产）+ Mockito 的档案查询，
 * 所以这些断言跑的就是线上那条路径，只是把数据库换成了固定的几行。</p>
 */
class TerrainSightServiceTest {

    private TerrainSightService service;
    private MonitorPointMapper pointMapper;
    private MonitorObjectMapper objectMapper;
    private SceneMapper sceneMapper;
    private DigitalTwinSceneMapper twinSceneMapper;

    @BeforeAll
    static void warmLambdaCaches() {
        // 纯 Mockito 单测没有 MyBatis-Plus 启动流程，wrapper 的列名解析会找不到缓存
        MybatisPlusLambdaCache.warm(DigitalTwinScene.class);
    }

    @BeforeEach
    void setUp() {
        pointMapper = mock(MonitorPointMapper.class);
        objectMapper = mock(MonitorObjectMapper.class);
        sceneMapper = mock(SceneMapper.class);
        twinSceneMapper = mock(DigitalTwinSceneMapper.class);
        TerrainHeightfieldService fields = new TerrainHeightfieldService(
                new ObjectMapper(), new DefaultResourceLoader());
        fields.reload();
        service = new TerrainSightService(fields, pointMapper, objectMapper, sceneMapper, twinSceneMapper);
    }

    /** 把某测点挂到项目 1 的山地场景上（资产版本可指定，用来造"没有高程场"的那种）。 */
    private void attachToScene(Long pointId, String assetVersion, boolean enabled) {
        MonitorObject object = new MonitorObject();
        object.setId(1L);
        object.setSceneId(1L);
        Scene scene = new Scene();
        scene.setId(1L);
        scene.setProjectId(1L);
        DigitalTwinScene twin = new DigitalTwinScene();
        twin.setProjectId(1L);
        twin.setEnabled(enabled);
        twin.setAssetVersion(assetVersion);
        when(objectMapper.selectById(any())).thenReturn(object);
        when(sceneMapper.selectById(any())).thenReturn(scene);
        when(twinSceneMapper.selectOne(any())).thenReturn(twin);
        when(pointMapper.selectById(pointId)).thenReturn(point(pointId));
    }

    private static MonitorPoint point(Long id) {
        MonitorPoint p = new MonitorPoint();
        p.setId(id);
        p.setObjectId(1L);
        return p;
    }

    private static Device radarOne() {
        Device d = new Device();
        d.setId(1L);
        d.setCode("radar-001");
        d.setLongitude(new BigDecimal("113.0528015"));
        d.setLatitude(new BigDecimal("23.7621687"));
        d.setAltitude(new BigDecimal("131.001"));
        d.setAntennaHeightM(new BigDecimal("10.000"));
        d.setHeadingDegrees(new BigDecimal("226.683"));
        d.setPitchDegrees(new BigDecimal("9.484"));
        d.setDetectionRangeM(new BigDecimal("620.000"));
        d.setHalfAngleDegrees(new BigDecimal("30.000"));
        d.setVerticalHalfAngleDegrees(new BigDecimal("15.000"));
        return d;
    }

    /** 山脊测点 1（V19 的档案坐标）：应与迁移里记的一致——通视、净空 1.438m。 */
    private static MonitorPoint ridgePointOne() {
        MonitorPoint p = point(1L);
        p.setCode("P-HK01");
        p.setLongitude(new BigDecimal("113.0498585"));
        p.setLatitude(new BigDecimal("23.7612658"));
        p.setAltitude(new BigDecimal("211.734"));
        return p;
    }

    @Test
    void previewReproducesTheMigratedSight() {
        attachToScene(1L, "qingyuan-hillside-2.0.0", true);
        DevicePoint binding = new DevicePoint();
        binding.setAzimuthDegrees(new BigDecimal("251.565"));
        binding.setElevationDegrees(new BigDecimal("13.039"));
        binding.setSlantRangeM(new BigDecimal("324.597"));
        binding.setReflectorHeightM(new BigDecimal("2.500"));

        CalibrationPreviewVO vo = service.preview(radarOne(), ridgePointOne(), binding, null);

        assertTrue(vo.isSceneAvailable());
        assertTrue(vo.isInsideModel());
        assertEquals("VISIBLE", vo.getVerdict());
        assertEquals(1.438, vo.getMinimumClearanceM(), 0.01);
        assertEquals(251.565, vo.getComputedAzimuthDegrees(), 0.01);
        assertEquals(13.039, vo.getComputedElevationDegrees(), 0.01);
        assertEquals(324.597, vo.getComputedSlantRangeM(), 0.01);
        assertEquals(0.0, vo.getAzimuthDeltaDegrees(), 0.05, "绑定值与档案几何同源，差值应约等于 0");
        assertEquals(0.0, vo.getSlantRangeDeltaM(), 0.05);
        assertTrue(vo.getWithinDetectionRange());
        assertTrue(vo.getWithinHorizontalFov());
        assertTrue(vo.getWithinVerticalFov());
        assertEquals("qingyuan-hillside-2.0.0", vo.getAssetVersion());
        assertNotNull(vo.getSamplingSteps());
        assertNull(service.lineOfSightBlockReason(radarOne(), ridgePointOne(), new BigDecimal("2.5")));
    }

    @Test
    void blockedTargetIsReportedAndRejected() {
        attachToScene(99L, "qingyuan-hillside-2.0.0", true);
        MonitorPoint behindTheRidge = point(99L);
        behindTheRidge.setCode("P-HIDDEN");
        // 本地 (-400,-300)：在山脊另一侧的谷底，从北侧雷达看过去整条视线穿地
        behindTheRidge.setLongitude(new BigDecimal("113.0474061"));
        behindTheRidge.setLatitude(new BigDecimal("23.7567513"));
        behindTheRidge.setAltitude(new BigDecimal("60.301"));

        CalibrationPreviewVO vo = service.preview(radarOne(), behindTheRidge, null, null);

        assertEquals("BLOCKED", vo.getVerdict());
        assertEquals(-75.146, vo.getMinimumClearanceM(), 0.05);
        assertFalse(vo.getTerrainLineOfSight());
        assertTrue(vo.getSummary().contains("遮挡"));
        String reason = service.lineOfSightBlockReason(radarOne(), behindTheRidge, new BigDecimal("2.5"));
        assertNotNull(reason, "声称通视但地形判定被遮挡时必须拒收");
        assertTrue(reason.contains("遮挡"));
    }

    /**
     * 试算可以临时抬高天线，但**净空不会跟着等量抬**——这条正是"试算"和"拍脑袋"的区别。
     *
     * <p>天线从 10m 抬到 20m，P-HK01 的净空只从 1.438m 涨到 2.185m（+0.75m）：
     * 卡住这条视线的地形离雷达很远、紧贴目标，抬高雷达只是把整条射线平行地抬高一点点
     * （几何上净空增益 ≈ 抬高量 × 遮挡点距目标的距离比）。想靠"把雷达架高"解决遮挡，
     * 在现场往往要架得比想象中高得多——这个数是试算端点唯一能提前告诉人的事。</p>
     */
    @Test
    void raisingTheAntennaDoesNotLiftClearanceOneForOne() {
        attachToScene(1L, "qingyuan-hillside-2.0.0", true);
        CalibrationPreviewRequest request = new CalibrationPreviewRequest();
        request.setAntennaHeightM(new BigDecimal("20.0"));
        CalibrationPreviewVO vo = service.preview(radarOne(), ridgePointOne(), null, request);
        assertEquals(20.0, vo.getAntennaHeightUsedM().doubleValue(), 1e-9);
        assertEquals(2.5, vo.getReflectorHeightUsedM().doubleValue(), 1e-9,
                "没绑定时反射器高取生成器的默认值 2.5m");
        assertEquals(2.185, vo.getMinimumClearanceM(), 0.01);
        assertTrue(vo.getMinimumClearanceM() > 1.438, "抬高天线仍然是有收益的，只是收益远小于抬高量");
    }

    @Test
    void missingTerrainAssetDoesNotBlock() {
        attachToScene(1L, "mountain-demo-2.0.0", true);
        CalibrationPreviewVO vo = service.preview(radarOne(), ridgePointOne(), null, null);
        assertEquals("NO_TERRAIN", vo.getVerdict());
        assertFalse(vo.isSceneAvailable());
        assertTrue(vo.getUnavailableReason().contains("mountain-demo-2.0.0"));
        assertNull(service.lineOfSightBlockReason(radarOne(), ridgePointOne(), null),
                "没有高程场就没有发言权：不能凭缺失去否决一次标定");
    }

    @Test
    void disabledSceneDoesNotBlock() {
        attachToScene(1L, "qingyuan-hillside-2.0.0", false);
        CalibrationPreviewVO vo = service.preview(radarOne(), ridgePointOne(), null, null);
        assertEquals("NO_TERRAIN", vo.getVerdict());
        assertNull(service.lineOfSightBlockReason(radarOne(), ridgePointOne(), null));
    }

    @Test
    void pointOutsideTheModelGetsNoClearance() {
        attachToScene(98L, "qingyuan-hillside-2.0.0", true);
        MonitorPoint far = point(98L);
        far.setLongitude(new BigDecimal("113.0807591"));
        far.setLatitude(new BigDecimal("23.7594600"));
        far.setAltitude(new BigDecimal("120.000"));

        CalibrationPreviewVO vo = service.preview(radarOne(), far, null, null);
        assertEquals("OUTSIDE_MODEL", vo.getVerdict());
        assertFalse(vo.isInsideModel());
        assertNull(vo.getMinimumClearanceM(), "范围外不给采样值，免得夹到边界的数被当成结论");
        assertNull(service.lineOfSightBlockReason(radarOne(), far, null));
    }

    @Test
    void missingArchiveGeometryIsReportedAsSuch() {
        attachToScene(1L, "qingyuan-hillside-2.0.0", true);
        Device noAltitude = radarOne();
        noAltitude.setAltitude(null);
        CalibrationPreviewVO vo = service.preview(noAltitude, ridgePointOne(), null, null);
        assertEquals("GEOMETRY_MISSING", vo.getVerdict());
        assertTrue(vo.getUnavailableReason().contains("设备档案"));
        assertNull(service.lineOfSightBlockReason(noAltitude, ridgePointOne(), null));
    }

    /** 试算端点可以在**尚未绑定**时使用：这正是"先看看能不能看到、行的话再绑"的场景。 */
    @Test
    void previewWorksWithoutBinding() {
        attachToScene(1L, "qingyuan-hillside-2.0.0", true);
        CalibrationPreviewVO vo = service.preview(radarOne(), ridgePointOne(), null, null);
        assertEquals("VISIBLE", vo.getVerdict());
        assertNull(vo.getBoundAzimuthDegrees());
        assertNull(vo.getAzimuthDeltaDegrees());
    }

    @Test
    void unmappedPointHasNoScene() {
        when(objectMapper.selectById(any())).thenReturn(null);
        MonitorPoint orphan = ridgePointOne();
        orphan.setId(7L);
        CalibrationPreviewVO vo = service.preview(radarOne(), orphan, null, null);
        assertEquals("NO_TERRAIN", vo.getVerdict());
        assertTrue(vo.getUnavailableReason().contains("未归属"));
        assertNull(service.lineOfSightBlockReason(radarOne(), orphan, null));
    }
}
