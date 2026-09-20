package com.monitor.twin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.monitor.twin.dto.RadarCoverageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 雷达地面覆盖的**地形裁剪**计算（复查清单 P1-11 的后半）。
 *
 * <p><b>它解决什么</b>：大屏上那一片扇形是按量程与角度解析画出来的，看起来像"覆盖到了"，
 * 而山脊后面的坡面根本收不到回波。这个服务沿每条方位线从雷达脚下往外走，遇到"地面落到
 * 视线之下"就停，于是扇面的外缘会跟着山脊线走，而不是画成一个完美的圆弧。</p>
 *
 * <p><b>判据（与生成器、与标定校核同一套口径）</b>：地面点可见 ⟺ 它相对雷达头的仰角
 * **严格大于**此前所有采样点的最大仰角（经典的水平线算法）。只用到已有的高程场与逐点仰角，
 * 没有引入第二套几何。</p>
 *
 * <p><b>三个刻意的不做</b>：</p>
 * <ul>
 *   <li>不做"越过山脊还能看到远处山尖"的重见区——那确实存在，但它不是能布测点的连续区域；
 *       返回的是**连续可见**的那一段，说明写在响应 note 里；</li>
 *   <li>不把垂直视场一起裁（那是三维锥体与地形求交，画在俯视扇面上也表达不出来）；</li>
 *   <li>不做植被/建筑遮挡——地形资产里只有地面，这一点在界面上也写清楚。</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TerrainCoverageService {

    /** 方位线采样步长（度）。±30° 视场就是 61 条线，肉眼足够平滑。 */
    public static final double STEP_DEGREES = 1.0;

    /** 沿方位线的距离步长（米）。2m 在 620m 量程上是 310 步，单台雷达几十毫秒。 */
    public static final double STEP_METERS = 2.0;

    /**
     * 测点标记高度（0.35m）与反射器高度（2.5m）之和——与生成器写库、与标定核验用的是同一个口径。
     *
     * <p>这个值必须与 {@link TerrainSightService} 的目标高度**逐项相同**，否则扇面与逐目标结论
     * 会在边界上互相矛盾：第一次实现只取了 2.5（漏了标记的 0.35），交叉校验立刻抓出来
     * ——"已核验可见的目标落在扇面之外"。</p>
     */
    private static final double REFLECTOR_HEIGHT_M = 2.85;
    private static final int SCALE = 3;

    private final TerrainHeightfieldService heightfields;
    private final MonitorPointMapper pointMapper;
    private final MonitorObjectMapper objectMapper;
    private final SceneMapper sceneMapper;
    private final DigitalTwinSceneMapper twinSceneMapper;
    private final DevicePointMapper devicePointMapper;

    /** 设备所在项目的数字孪生资产版本 → 高程场（设备 → 绑定测点 → 对象 → 场景 → 项目）。 */
    Optional<TerrainHeightfield> terrainOf(Long deviceId) {
        List<Long> pointIds = devicePointMapper.selectList(new LambdaQueryWrapper<DevicePoint>()
                        .eq(DevicePoint::getDeviceId, deviceId))
                .stream().map(DevicePoint::getPointId).filter(Objects::nonNull).toList();
        if (pointIds.isEmpty()) {
            return Optional.empty();
        }
        for (MonitorPoint p : pointMapper.selectByIds(pointIds)) {
            if (p.getObjectId() == null) {
                continue;
            }
            MonitorObject object = objectMapper.selectById(p.getObjectId());
            Scene scene = object == null || object.getSceneId() == null
                    ? null : sceneMapper.selectById(object.getSceneId());
            if (scene == null || scene.getProjectId() == null) {
                continue;
            }
            DigitalTwinScene twin = twinSceneMapper.selectOne(
                    new LambdaQueryWrapper<DigitalTwinScene>()
                            .eq(DigitalTwinScene::getProjectId, scene.getProjectId())
                            .last("LIMIT 1"));
            if (twin == null || !Boolean.TRUE.equals(twin.getEnabled())) {
                continue;
            }
            Optional<TerrainHeightfield> field = heightfields.byAssetVersion(twin.getAssetVersion());
            if (field.isPresent()) {
                return field;
            }
        }
        return Optional.empty();
    }

    /** 计算一台雷达的地面覆盖；没有可用高程场时返回 terrainAvailable=false 与原因。 */
    public RadarCoverageVO coverage(Device device) {
        RadarCoverageVO vo = new RadarCoverageVO();
        vo.setDeviceId(device.getId());
        vo.setCode(device.getCode());
        vo.setHeadingDegrees(round(value(device.getHeadingDegrees())));
        vo.setHalfAngleDegrees(round(value(device.getHalfAngleDegrees())));
        vo.setDetectionRangeM(round(value(device.getDetectionRangeM())));
        vo.setAntennaHeightM(round(value(device.getAntennaHeightM())));
        vo.setReflectorHeightM(REFLECTOR_HEIGHT_M);
        vo.setStepDegrees(STEP_DEGREES);
        vo.setStepMeters(STEP_METERS);

        if (device.getLongitude() == null || device.getLatitude() == null
                || device.getAltitude() == null || device.getHeadingDegrees() == null
                || device.getHalfAngleDegrees() == null || device.getDetectionRangeM() == null) {
            vo.setUnavailableReason("设备档案缺少经纬度/高程/航向/视场/量程");
            vo.setNote("档案不全就算不出覆盖：缺一项都会让结果变成看起来有、其实没依据。");
            return vo;
        }
        Optional<TerrainHeightfield> maybeField = terrainOf(device.getId());
        if (maybeField.isEmpty()) {
            vo.setUnavailableReason("该项目没有可用的离线地形高程场（或设备尚未绑定到任何测点）");
            vo.setNote("没有高程场时只画理论视场；覆盖层不出现，也不冒充有结论。");
            return vo;
        }
        TerrainHeightfield field = maybeField.get();
        vo.setTerrainAvailable(true);
        vo.setAssetVersion(field.assetVersion());

        double headX = field.xOf(device.getLongitude().doubleValue());
        double headY = field.yOf(device.getLatitude().doubleValue());
        vo.setHeadGroundAltitudeM(round(field.groundAbsolute(headX, headY)));
        double headZ = device.getAltitude().doubleValue() + value(device.getAntennaHeightM());

        double heading = device.getHeadingDegrees().doubleValue();
        double half = Math.max(0.1, device.getHalfAngleDegrees().doubleValue());
        double range = Math.max(1.0, device.getDetectionRangeM().doubleValue());
        double shortest = Double.POSITIVE_INFINITY;
        boolean clipped = false;
        for (double offset = -half; offset <= half + 1e-9; offset += STEP_DEGREES) {
            RadarCoverageVO.Ray ray = castRay(field, headX, headY, headZ,
                    (heading + offset + 360.0) % 360.0, range);
            vo.getRays().add(ray);
            shortest = Math.min(shortest, ray.getVisibleDistanceM());
            clipped = clipped || Boolean.TRUE.equals(ray.getBlocked());
        }
        // 半角不能被步长整除时补最后一条：否则扇面边缘会缺一角（±30 / 1.0 正好整除，
        // 但换个步长或换个设备就会露出来——这种缺口只有对着一张图才看得出来）。
        double lastOffset = vo.getRays().size() * STEP_DEGREES - half;
        if (lastOffset < half - 1e-9) {
            RadarCoverageVO.Ray ray = castRay(field, headX, headY, headZ,
                    (heading + half + 360.0) % 360.0, range);
            vo.getRays().add(ray);
            shortest = Math.min(shortest, ray.getVisibleDistanceM());
            clipped = clipped || Boolean.TRUE.equals(ray.getBlocked());
        }
        vo.setTerrainClipped(clipped);
        vo.setShortestVisibleDistanceM(round(shortest));
        vo.setNote(clipped
                ? "覆盖外缘按地形裁剪：沿每条方位线从雷达脚下往外，地面落到视线之下即止（山脊遮挡）。"
                  + "判据是目标点（地面 + 标记 0.35m + 反射器 2.5m）仰角严格大于此前最大地形仰角，"
                  + "与标定校核同一套口径。"
                  + "不含植被与建筑，也不含越过山脊后仍在视线里的远山（那不是连续可布点区）。"
                : "本台雷达在这个量程内没有被地形截短：每条方位线都一路看到量程边。");
        return vo;
    }

    /**
     * 沿一条方位线从近到远，返回**连续可见**的那一段。
     *
     * <p>{@code azimuth} 按 0°=北、顺时针为正（与设备 heading_degrees 同口径）。</p>
     *
     * <p><b>判据为什么是"目标仰角 vs 前缀最大「地形」仰角"</b>（而不是两个都带上反射器高）：</p>
     *
     * <p>要判的是"这个目标点能不能被看见"，而挡光的是**地面本身**。严格推一遍：
     * 点 P（地面 + 反射器高）可见 ⟺ 对射线上所有更近的点 q，
     * 都有 {@code terrain(q) < 割线高度(q)} ⟺ {@code angle(target_P) > angle(terrain_q)}。</p>
     *
     * <p>第一版把两边都加了反射器高，于是"挡光物也被抬高了 2.85m"，判据偏保守：
     * 它会声称一条**实际通视 1.4m** 的视线被挡住（本仓的 22 号套件里那条
     * "已核验目标必须落在扇面内"的交叉校验当场抓到了这个矛盾）。</p>
     */
    RadarCoverageVO.Ray castRay(TerrainHeightfield field, double headX, double headY,
                                double headZ, double azimuth, double range) {
        double rad = Math.toRadians(azimuth);
        double dirEast = Math.sin(rad);
        double dirNorth = Math.cos(rad);
        // 前缀最大**地形**仰角：它才是"挡光的东西"有多高
        double maxTerrainAngle = Double.NEGATIVE_INFINITY;
        double visible = 0.0;
        double groundAtVisible = field.groundAbsolute(headX, headY);
        boolean blocked = false;
        for (double d = STEP_METERS; d <= range + 1e-9; d += STEP_METERS) {
            double x = headX + dirEast * d;
            double y = headY + dirNorth * d;
            if (!field.covers(x, y)) {
                // 走出模型范围：后端对范围外的地形没有发言权，按"到此为止"处理并标记被截短
                blocked = true;
                break;
            }
            double ground = field.groundAbsolute(x, y);
            double terrainAngle = Math.atan2(ground - headZ, d);
            double targetAngle = Math.atan2(ground + REFLECTOR_HEIGHT_M - headZ, d);
            if (targetAngle <= maxTerrainAngle) {
                blocked = true;
                break;
            }
            maxTerrainAngle = Math.max(maxTerrainAngle, terrainAngle);
            visible = d;
            groundAtVisible = ground;
        }
        return new RadarCoverageVO.Ray(round(azimuth), round(visible), round(groundAtVisible),
                blocked);
    }

    private static double value(BigDecimal v) {
        return v == null ? 0.0 : v.doubleValue();
    }

    private static Double round(double v) {
        return BigDecimal.valueOf(v).setScale(SCALE, RoundingMode.HALF_UP).doubleValue();
    }
}
