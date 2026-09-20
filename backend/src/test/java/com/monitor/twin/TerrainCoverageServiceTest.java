package com.monitor.twin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.asset.entity.Device;
import com.monitor.asset.entity.DevicePoint;
import com.monitor.asset.mapper.DevicePointMapper;
import com.monitor.project.entity.DigitalTwinScene;
import com.monitor.project.entity.MonitorObject;
import com.monitor.project.entity.MonitorPoint;
import com.monitor.project.entity.Scene;
import com.monitor.project.mapper.DigitalTwinSceneMapper;
import com.monitor.project.mapper.MonitorObjectMapper;
import com.monitor.project.mapper.MonitorPointMapper;
import com.monitor.project.mapper.SceneMapper;
import com.monitor.support.MybatisPlusLambdaCache;
import com.monitor.twin.dto.RadarCoverageVO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 地形裁剪覆盖（P1-11 后半）：沿方位线的连续可见距离。
 *
 * <p>三条断言各自钉一件事：平地不该被"裁"（假阳性最伤人——那会让界面上出现一片
 * 本不存在的遮挡）；真被山脊挡住时必须截短；没有高程场时不装懂。</p>
 */
class TerrainCoverageServiceTest {

    private TerrainCoverageService service;
    private MonitorPointMapper pointMapper;
    private MonitorObjectMapper objectMapper;
    private SceneMapper sceneMapper;
    private DigitalTwinSceneMapper twinSceneMapper;
    private DevicePointMapper devicePointMapper;

    @BeforeAll
    static void warm() {
        MybatisPlusLambdaCache.warm(DigitalTwinScene.class);
    }

    @BeforeEach
    void setUp() {
        pointMapper = mock(MonitorPointMapper.class);
        objectMapper = mock(MonitorObjectMapper.class);
        sceneMapper = mock(SceneMapper.class);
        twinSceneMapper = mock(DigitalTwinSceneMapper.class);
        devicePointMapper = mock(DevicePointMapper.class);
        TerrainHeightfieldService fields = new TerrainHeightfieldService(
                new ObjectMapper(), new DefaultResourceLoader());
        fields.reload();
        service = new TerrainCoverageService(fields, pointMapper, objectMapper, sceneMapper,
                twinSceneMapper, devicePointMapper);
    }

    private void bindToScene(String assetVersion, boolean enabled) {
        DevicePoint binding = new DevicePoint();
        binding.setDeviceId(1L);
        binding.setPointId(1L);
        when(devicePointMapper.selectList(any())).thenReturn(List.of(binding));
        MonitorPoint p = new MonitorPoint();
        p.setId(1L);
        p.setObjectId(1L);
        when(pointMapper.selectByIds(any())).thenReturn(List.of(p));
        MonitorObject object = new MonitorObject();
        object.setSceneId(1L);
        when(objectMapper.selectById(anyLong())).thenReturn(object);
        Scene scene = new Scene();
        scene.setProjectId(1L);
        when(sceneMapper.selectById(any())).thenReturn(scene);
        DigitalTwinScene twin = new DigitalTwinScene();
        twin.setEnabled(enabled);
        twin.setAssetVersion(assetVersion);
        when(twinSceneMapper.selectOne(any())).thenReturn(twin);
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

    @Test
    void flatTerrainIsNotFalselyClipped() {
        // 全零网格：地面绝对高程恒为 100，雷达头在 110 -> 每条方位线都应看到量程边
        TerrainHeightfield flat = new TerrainHeightfield("flat-test", 113.0, 23.0, 100.0,
                2000.0, 2000.0, 100000.0, 110000.0, 100, 100, new float[101 * 101]);
        RadarCoverageVO.Ray ray = service.castRay(flat, 0, 0, 110, 0, 400);
        assertThat(ray.getBlocked()).isFalse();
        assertThat(ray.getVisibleDistanceM()).isEqualTo(400.0);
        assertThat(ray.getGroundAltitudeM()).isEqualTo(100.0);
    }

    /** 山脊：在+北方向 100m 处堆一道 40m 高的坎，它后面的地面必须被截掉。 */
    @Test
    void ridgeCutsTheRayShort() {
        TerrainHeightfield field = ridgeField();
        RadarCoverageVO.Ray ray = service.castRay(field, 0, -400, 130, 0, 600);
        assertThat(ray.getBlocked()).isTrue();
        // 坎在 y=-300..-200（本地坐标），从 y=-400 出发 -> 可见距离远小于量程
        assertThat(ray.getVisibleDistanceM()).isLessThan(400.0);
        assertThat(ray.getVisibleDistanceM()).isGreaterThan(50.0);
    }

    @Test
    void coverageUsesEveryAzimuthAcrossTheFieldOfView() {
        bindToScene("qingyuan-hillside-2.0.0", true);
        RadarCoverageVO vo = service.coverage(radarOne());
        assertThat(vo.isTerrainAvailable()).isTrue();
        assertThat(vo.getAssetVersion()).isEqualTo("qingyuan-hillside-2.0.0");
        // -30..+30 每 1° 一条 = 61 条
        assertThat(vo.getRays()).hasSize(61);
        assertThat(vo.getRays().get(0).getAzimuthDegrees())
                .isEqualTo(round(226.683 - 30));
        assertThat(vo.getRays().get(60).getAzimuthDegrees()).isEqualTo(round(226.683 + 30));
        // 与设备档案里的 131.001 差 2mm：档案经纬度只有 7 位小数，这里是从经纬度反算回
        // 本地坐标再采样得到的（与 P1-10 净空那处同源的往返误差），所以带容差比。
        assertThat(vo.getHeadGroundAltitudeM()).isCloseTo(131.001, org.assertj.core.data.Offset.offset(0.05));
        // 山脊场景里北侧雷达南望必然被截短
        assertThat(vo.isTerrainClipped()).isTrue();
        assertThat(vo.getShortestVisibleDistanceM()).isLessThan(620.0);
        assertThat(vo.getNote()).contains("地形裁剪");
    }

    @Test
    void withoutTerrainItSaysSoInsteadOfGuessing() {
        bindToScene("mountain-demo-2.0.0", true);
        RadarCoverageVO vo = service.coverage(radarOne());
        assertThat(vo.isTerrainAvailable()).isFalse();
        assertThat(vo.getRays()).isEmpty();
        assertThat(vo.getUnavailableReason()).contains("高程场");
        assertThat(vo.getNote()).contains("只画理论视场");
    }

    @Test
    void incompleteArchiveIsRejectedNotGuessed() {
        bindToScene("qingyuan-hillside-2.0.0", true);
        Device broken = radarOne();
        broken.setHeadingDegrees(null);
        RadarCoverageVO vo = service.coverage(broken);
        assertThat(vo.isTerrainAvailable()).isFalse();
        assertThat(vo.getUnavailableReason()).contains("档案缺少");
    }

    private static double round(double v) {
        return BigDecimal.valueOf(v).setScale(3, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    /** 造一片"雷达在南侧低处、北面 100m 处一道 40m 高的坎"的地形。 */
    private static TerrainHeightfield ridgeField() {
        int cells = 100;
        float[] grid = new float[(cells + 1) * (cells + 1)];
        // 本地坐标 -1000..1000；把 y ∈ [-300,-200] 那一段抬高 40m
        for (int j = 0; j <= cells; j++) {
            double y = -1000 + 20.0 * j;
            if (y >= -300 && y <= -200) {
                for (int i = 0; i <= cells; i++) {
                    grid[j * (cells + 1) + i] = 40f;
                }
            }
        }
        return new TerrainHeightfield("ridge-test", 113.0, 23.0, 100.0,
                2000.0, 2000.0, 100000.0, 110000.0, cells, cells, grid);
    }
}
