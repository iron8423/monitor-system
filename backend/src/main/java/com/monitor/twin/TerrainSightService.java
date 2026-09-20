package com.monitor.twin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.monitor.twin.dto.CalibrationPreviewRequest;
import com.monitor.twin.dto.CalibrationPreviewVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * 「这条视线到底看不看得见」的唯一后端口径（复查清单 P1-10）。
 *
 * <p>此前 {@code device_point.line_of_sight} 是**离线生成器写进迁移的一个布尔**：
 * 平台自己没有地形，也没有任何办法复核它。新绑定的目标、被挪动过的雷达，都只能靠人填。
 * 现在补齐了另一半——后端拿到同一份高程场，能按档案几何重算，并且：</p>
 * <ul>
 *   <li>标定前可以<b>试算</b>（只算不写）：给现场一个"这么装行不行"的答案；</li>
 *   <li>提交标定且声称"通视"时，若地形模型判定被遮挡则<b>拒收</b>——
 *       这正是"人工填错没人拦"里最该拦的一种。</li>
 * </ul>
 *
 * <p><b>拦的边界写清楚</b>：只在"该项目有高程场 + 两端点都在模型覆盖范围内 + 档案坐标齐全"
 * 三条同时成立时才下结论；任一不成立就放行（并说明原因）。理由是这个地形来自公开 30m DEM，
 * 在场景边缘或场景之外它没有发言权——拿一个夹到边上的采样值去否决现场实测，比不拦更坏。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TerrainSightService {

    /** 反射器高于测点标记的默认值（与生成器 {@code DEFAULT_REFLECTOR_HEIGHT_M} 一致）。 */
    public static final BigDecimal DEFAULT_REFLECTOR_HEIGHT_M = new BigDecimal("2.5");

    private static final int SCALE = 3;

    private final TerrainHeightfieldService heightfields;
    private final MonitorPointMapper pointMapper;
    private final MonitorObjectMapper objectMapper;
    private final SceneMapper sceneMapper;
    private final DigitalTwinSceneMapper twinSceneMapper;

    /** 高程场解析结果：拿到网格、或拿到"为什么拿不到"。 */
    private record Terrain(String assetVersion, TerrainHeightfield field, String unavailableReason) {
    }

    /** 场景内的一次视线解算（绝对高程，米）。 */
    private record Solution(Terrain terrain, TerrainHeightfield field,
                            double headX, double headY, double headZ,
                            double targetX, double targetY, double targetZ,
                            LineOfSight.Result sight) {
    }

    // ------------------------------------------------------------ 试算

    /**
     * 按档案几何试算一次标定（不写库）。
     *
     * @param binding 当前绑定，可为 null（刚绑上还没标定时就是 null）
     */
    public CalibrationPreviewVO preview(Device device, MonitorPoint point, DevicePoint binding,
                                        CalibrationPreviewRequest request) {
        BigDecimal antenna = request == null ? null : request.getAntennaHeightM();
        BigDecimal reflector = request == null ? null : request.getReflectorHeightM();
        if (antenna == null) {
            antenna = device.getAntennaHeightM();
        }
        if (reflector == null) {
            reflector = binding == null || binding.getReflectorHeightM() == null
                    ? DEFAULT_REFLECTOR_HEIGHT_M : binding.getReflectorHeightM();
        }

        CalibrationPreviewVO vo = new CalibrationPreviewVO();
        vo.setAntennaHeightUsedM(antenna);
        vo.setReflectorHeightUsedM(reflector);

        String missing = missingGeometry(device, point);
        if (missing != null) {
            vo.setVerdict("GEOMETRY_MISSING");
            vo.setUnavailableReason(missing);
            vo.setSummary("档案坐标不全，无法试算：" + missing);
            vo.getNotes().add("试算用的是设备与测点档案里的经纬度/高程，不是本次请求里填的方位角与斜距。");
            fillBinding(vo, binding);
            return vo;
        }

        Solution solution = solve(device, point, antenna, reflector);
        Terrain terrain = solution.terrain();
        vo.setAssetVersion(terrain.assetVersion());
        vo.setSceneAvailable(terrain.field() != null);
        if (terrain.field() == null) {
            vo.setVerdict("NO_TERRAIN");
            vo.setUnavailableReason(terrain.unavailableReason());
            vo.setSummary("该项目没有可用的离线地形高程场，无法做通视校核。");
            vo.getNotes().add("高程场由 tools/terrain_asset/export_heightfield.py 从同一份 DEM 素材导出。");
            fillBinding(vo, binding);
            return vo;
        }
        double dx = solution.targetX() - solution.headX();
        double dy = solution.targetY() - solution.headY();
        double horizontal = Math.hypot(dx, dy);
        double dz = solution.targetZ() - solution.headZ();
        // 范围外一律不给数：采样会把范围外的点夹到边界上，那个值看着像结论、其实没有依据。
        if (!terrain.field().covers(solution.headX(), solution.headY())
                || !terrain.field().covers(solution.targetX(), solution.targetY())) {
            vo.setInsideModel(false);
            vo.setVerdict("OUTSIDE_MODEL");
            vo.setUnavailableReason("设备头或目标落在模型覆盖范围之外");
            vo.setSummary("端点超出离线地形模型范围（%s：东西 %.0fm × 南北 %.0fm），无法给出通视结论。"
                    .formatted(terrain.assetVersion(), terrain.field().width(), terrain.field().depth()));
            vo.getNotes().add("模型范围外不给采样值：把范围外的点夹到边界上算出来的净空没有依据。");
            fillBinding(vo, binding);
            return vo;
        }
        vo.setInsideModel(true);

        vo.setComputedAzimuthDegrees(round(LineOfSight.bearingDegrees(dx, dy)));
        vo.setComputedElevationDegrees(round(LineOfSight.elevationDegrees(dz, horizontal)));
        vo.setComputedSlantRangeM(round(LineOfSight.slantRangeM(horizontal, dz)));
        vo.setHorizontalDistanceM(round(horizontal));
        vo.setTerrainLineOfSight(solution.sight().visible());
        vo.setMinimumClearanceM(round(solution.sight().minimumClearanceM()));
        vo.setSamplingSteps(solution.sight().steps());
        vo.setVerdict(solution.sight().visible() ? "VISIBLE" : "BLOCKED");

        fillFov(vo, device, dy == 0 && dx == 0 ? 0 : LineOfSight.bearingDegrees(dx, dy),
                LineOfSight.elevationDegrees(dz, horizontal), LineOfSight.slantRangeM(horizontal, dz));
        fillBinding(vo, binding);
        fillSummary(vo, device);
        return vo;
    }

    /**
     * 标定提交前的地形体检。
     *
     * @return 应当拒收时返回给调用方显示的原因；返回 {@code null} 表示不拦
     */
    public String lineOfSightBlockReason(Device device, MonitorPoint point, BigDecimal reflectorHeightM) {
        if (missingGeometry(device, point) != null) {
            return null;
        }
        BigDecimal antenna = device.getAntennaHeightM();
        if (antenna == null) {
            return null;
        }
        BigDecimal reflector = reflectorHeightM == null ? DEFAULT_REFLECTOR_HEIGHT_M : reflectorHeightM;
        Solution solution;
        try {
            solution = solve(device, point, antenna, reflector);
        } catch (RuntimeException e) {
            log.warn("地形通视校核异常，放行本次标定：{}", e.getMessage());
            return null;
        }
        if (solution.field() == null || solution.sight().visible()) {
            return null;
        }
        if (!solution.terrain().field().covers(solution.headX(), solution.headY())
                || !solution.terrain().field().covers(solution.targetX(), solution.targetY())) {
            return null;
        }
        return "地形模型判定该目标被遮挡（最小净空 %.2fm，资产 %s），不能激活为通视标定；"
                .formatted(solution.sight().minimumClearanceM(), solution.terrain().assetVersion())
                + "若现场实测确实可见，请先核对设备/测点档案坐标与数字孪生资产版本。";
    }

    // ------------------------------------------------------------ 内部

    private Solution solve(Device device, MonitorPoint point, BigDecimal antenna, BigDecimal reflector) {
        Terrain terrain = resolveTerrain(point);
        if (terrain.field() == null) {
            return new Solution(terrain, null, 0, 0, 0, 0, 0, 0, null);
        }
        TerrainHeightfield field = terrain.field();
        double headX = field.xOf(device.getLongitude().doubleValue());
        double headY = field.yOf(device.getLatitude().doubleValue());
        double headZ = device.getAltitude().doubleValue() + antenna.doubleValue();
        double targetX = field.xOf(point.getLongitude().doubleValue());
        double targetY = field.yOf(point.getLatitude().doubleValue());
        double targetZ = point.getAltitude().doubleValue() + reflector.doubleValue();
        LineOfSight.Result sight = LineOfSight.compute(field, headX, headY, headZ,
                targetX, targetY, targetZ, LineOfSight.DEFAULT_MARGIN_M);
        return new Solution(terrain, field, headX, headY, headZ, targetX, targetY, targetZ, sight);
    }

    private String missingGeometry(Device device, MonitorPoint point) {
        if (device.getLongitude() == null || device.getLatitude() == null || device.getAltitude() == null) {
            return "设备档案缺少经纬度或高程";
        }
        if (point.getLongitude() == null || point.getLatitude() == null || point.getAltitude() == null) {
            return "测点档案缺少经纬度或高程";
        }
        return null;
    }

    private Terrain resolveTerrain(MonitorPoint point) {
        Long projectId = projectIdOfPoint(point);
        if (projectId == null) {
            return new Terrain(null, null, "测点未归属到任何项目");
        }
        DigitalTwinScene scene = twinSceneMapper.selectOne(new LambdaQueryWrapper<DigitalTwinScene>()
                .eq(DigitalTwinScene::getProjectId, projectId)
                .last("LIMIT 1"));
        if (scene == null || !Boolean.TRUE.equals(scene.getEnabled())) {
            return new Terrain(null, null, "项目 " + projectId + " 未启用数字孪生场景");
        }
        String version = scene.getAssetVersion();
        Optional<TerrainHeightfield> field = heightfields.byAssetVersion(version);
        if (field.isEmpty()) {
            return new Terrain(version, null,
                    "资产版本 " + version + " 没有后端高程场（当前可用："
                            + String.join("、", heightfields.availableVersions()) + "）");
        }
        return new Terrain(version, field.get(), null);
    }

    /** 测点 → 对象 → 场景 → 项目。任何一环断了都返回 null（与 DataScopeService 同一口径）。 */
    private Long projectIdOfPoint(MonitorPoint point) {
        if (point == null || point.getObjectId() == null) {
            return null;
        }
        MonitorObject object = objectMapper.selectById(point.getObjectId());
        if (object == null || object.getSceneId() == null) {
            return null;
        }
        Scene scene = sceneMapper.selectById(object.getSceneId());
        return scene == null ? null : scene.getProjectId();
    }

    private void fillFov(CalibrationPreviewVO vo, Device device, double azimuth, double elevation,
                         double slant) {
        vo.setWithinDetectionRange(device.getDetectionRangeM() == null
                ? null : slant <= device.getDetectionRangeM().doubleValue() + 1e-9);
        vo.setWithinHorizontalFov(device.getHalfAngleDegrees() == null
                ? null : LineOfSight.angularDifference(azimuth,
                        device.getHeadingDegrees() == null ? 0.0 : device.getHeadingDegrees().doubleValue())
                <= device.getHalfAngleDegrees().doubleValue() + 1e-9);
        vo.setWithinVerticalFov(device.getVerticalHalfAngleDegrees() == null
                ? null : Math.abs(elevation - (device.getPitchDegrees() == null
                        ? 0.0 : device.getPitchDegrees().doubleValue()))
                <= device.getVerticalHalfAngleDegrees().doubleValue() + 1e-9);
    }

    private void fillBinding(CalibrationPreviewVO vo, DevicePoint binding) {
        if (binding == null) {
            return;
        }
        vo.setBoundAzimuthDegrees(binding.getAzimuthDegrees());
        vo.setBoundElevationDegrees(binding.getElevationDegrees());
        vo.setBoundSlantRangeM(binding.getSlantRangeM());
        if (binding.getAzimuthDegrees() != null && vo.getComputedAzimuthDegrees() != null) {
            vo.setAzimuthDeltaDegrees(round(binding.getAzimuthDegrees().doubleValue()
                    - vo.getComputedAzimuthDegrees()));
        }
        if (binding.getSlantRangeM() != null && vo.getComputedSlantRangeM() != null) {
            vo.setSlantRangeDeltaM(round(binding.getSlantRangeM().doubleValue()
                    - vo.getComputedSlantRangeM()));
        }
    }

    private void fillSummary(CalibrationPreviewVO vo, Device device) {
        if ("VISIBLE".equals(vo.getVerdict())) {
            vo.setSummary("地形通视：全程最小净空 %.2fm，模型判定该目标可见。"
                    .formatted(vo.getMinimumClearanceM()));
        } else {
            vo.setSummary("地形遮挡：全程最小净空 %.2fm（负值即穿地而过），模型判定该目标不可见。"
                    .formatted(vo.getMinimumClearanceM()));
        }
        if (Boolean.FALSE.equals(vo.getWithinDetectionRange())) {
            vo.getNotes().add("斜距超出设备量程（模型斜距 %.1fm > 量程 %sm）：即使通视也收不到回波。"
                    .formatted(vo.getComputedSlantRangeM(), plain(device.getDetectionRangeM())));
        }
        if (Boolean.FALSE.equals(vo.getWithinHorizontalFov())) {
            vo.getNotes().add("目标方位超出当前航向的水平视场（航向 %s°，半角 %s°）：需要先转向再标定。"
                    .formatted(plain(device.getHeadingDegrees()), plain(device.getHalfAngleDegrees())));
        }
        if (Boolean.FALSE.equals(vo.getWithinVerticalFov())) {
            vo.getNotes().add("目标俯仰超出当前俯仰的垂直视场（俯仰 %s°，半角 %s°）。"
                    .formatted(plain(device.getPitchDegrees()), plain(device.getVerticalHalfAngleDegrees())));
        }
        if (vo.getAzimuthDeltaDegrees() != null && Math.abs(vo.getAzimuthDeltaDegrees()) > 1.0) {
            vo.getNotes().add("绑定里存的方位角与档案几何相差 %.1f°，两者不同源，请核对是哪一边过时了。"
                    .formatted(vo.getAzimuthDeltaDegrees()));
        }
        vo.getNotes().add("地形来自公开 30m DEM + 程序化细节（离线资产 %s），净空判据 > %.2fm，逐米步进 %d 次。"
                .formatted(vo.getAssetVersion(), LineOfSight.DEFAULT_MARGIN_M, vo.getSamplingSteps()));
        vo.getNotes().add("模型不能替代现场实测：边缘地带（模型范围外、陡坎、植被与临时堆载）以实测为准。");
    }

    private static Double round(double value) {
        return BigDecimal.valueOf(value).setScale(SCALE, RoundingMode.HALF_UP).doubleValue();
    }

    /** 数值进文案：null 显示成「未设置」，别让字符串里冒出一个 "null"。 */
    private static String plain(BigDecimal value) {
        return value == null ? "未设置" : value.stripTrailingZeros().toPlainString();
    }
}
