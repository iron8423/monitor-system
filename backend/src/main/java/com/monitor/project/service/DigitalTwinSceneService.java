package com.monitor.project.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.monitor.asset.DeviceStatusPolicy;
import com.monitor.asset.entity.Device;
import com.monitor.asset.entity.DevicePoint;
import com.monitor.asset.mapper.DeviceMapper;
import com.monitor.asset.mapper.DevicePointMapper;
import com.monitor.common.exception.BizException;
import com.monitor.project.dto.DigitalTwinSceneRequest;
import com.monitor.project.dto.DigitalTwinSceneVO;
import com.monitor.project.entity.DigitalTwinScene;
import com.monitor.project.entity.MonitorPoint;
import com.monitor.project.mapper.DigitalTwinSceneMapper;
import com.monitor.project.mapper.MonitorPointMapper;
import com.monitor.project.mapper.ProjectMapper;
import com.monitor.scope.service.DataScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 数字孪生配置的校验、按项目读取与幂等更新。 */
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class DigitalTwinSceneService {

    private final DigitalTwinSceneMapper sceneMapper;
    private final ProjectMapper projectMapper;
    private final DeviceMapper deviceMapper;
    private final DevicePointMapper devicePointMapper;
    private final MonitorPointMapper pointMapper;
    private final DataScopeService dataScope;

    public DigitalTwinSceneVO get(Long projectId) {
        requireProject(projectId);
        dataScope.assertProjectVisible(projectId);
        DigitalTwinScene scene = find(projectId);
        if (scene == null || !Boolean.TRUE.equals(scene.getEnabled())) {
            DigitalTwinSceneVO empty = new DigitalTwinSceneVO();
            empty.setProjectId(projectId);
            empty.setEnabled(false);
            empty.setAssetType("NONE");
            empty.setCoordinateMode("EMBEDDED");
            empty.setRadars(radarsOf(projectId));
            return empty;
        }
        DigitalTwinSceneVO vo = toVO(scene);
        vo.setRadars(radarsOf(projectId));
        return vo;
    }

    @Transactional
    public DigitalTwinSceneVO upsert(Long projectId, DigitalTwinSceneRequest request) {
        requireProject(projectId);
        validate(request);
        DigitalTwinScene scene = find(projectId);
        if (scene == null) {
            scene = new DigitalTwinScene();
            scene.setProjectId(projectId);
            copy(request, scene);
            sceneMapper.insert(scene);
        } else {
            copy(request, scene);
            sceneMapper.updateById(scene);
        }
        return get(projectId);
    }

    @Transactional
    public void disable(Long projectId) {
        requireProject(projectId);
        DigitalTwinScene scene = find(projectId);
        if (scene != null) {
            scene.setEnabled(false);
            sceneMapper.updateById(scene);
        }
    }

    private DigitalTwinScene find(Long projectId) {
        return sceneMapper.selectOne(new LambdaQueryWrapper<DigitalTwinScene>()
                .eq(DigitalTwinScene::getProjectId, projectId)
                .last("LIMIT 1"));
    }

    private void requireProject(Long projectId) {
        if (projectId == null || projectMapper.selectById(projectId) == null) {
            throw new BizException(404, "项目不存在: " + projectId);
        }
    }

    private void validate(DigitalTwinSceneRequest r) {
        if (r == null) {
            throw new BizException("数字孪生配置不能为空");
        }
        String type = upper(r.getAssetType());
        String mode = upper(r.getCoordinateMode());
        if (!"GLB".equals(type) && !"3D_TILES".equals(type)) {
            throw new BizException("assetType 仅支持 GLB / 3D_TILES");
        }
        if (!"ENU".equals(mode) && !"EMBEDDED".equals(mode)) {
            throw new BizException("coordinateMode 仅支持 ENU / EMBEDDED");
        }
        if ("GLB".equals(type) && !r.getAssetUrl().toLowerCase(Locale.ROOT).endsWith(".glb")) {
            throw new BizException("GLB 场景的 assetUrl 必须指向 .glb 文件");
        }
        if ("3D_TILES".equals(type) && !r.getAssetUrl().toLowerCase(Locale.ROOT).endsWith(".json")) {
            throw new BizException("3D_TILES 场景的 assetUrl 必须指向 tileset.json");
        }
        String url = r.getAssetUrl().trim();
        if (!(url.startsWith("/") || url.startsWith("https://") || url.startsWith("http://"))) {
            throw new BizException("assetUrl 必须是根相对路径或 HTTP(S) 地址");
        }
        if ("ENU".equals(mode)
                && (r.getAnchorLongitude() == null || r.getAnchorLatitude() == null)) {
            throw new BizException("ENU 场景必须提供 anchorLongitude / anchorLatitude");
        }
    }

    private List<DigitalTwinSceneVO.RadarPose> radarsOf(Long projectId) {
        List<Device> devices = deviceMapper.selectByProjectId(projectId);
        if (devices.isEmpty()) return List.of();
        Set<Long> deviceIds = devices.stream().map(Device::getId).collect(Collectors.toSet());
        List<DevicePoint> bindings = devicePointMapper.selectList(new LambdaQueryWrapper<DevicePoint>()
                .in(DevicePoint::getDeviceId, deviceIds));
        Set<Long> pointIds = bindings.stream().map(DevicePoint::getPointId).collect(Collectors.toSet());
        Map<Long, MonitorPoint> points = pointIds.isEmpty() ? Collections.emptyMap()
                : pointMapper.selectBatchIds(pointIds).stream()
                .collect(Collectors.toMap(MonitorPoint::getId, Function.identity(), (a, b) -> a));
        Map<Long, List<DevicePoint>> byDevice = bindings.stream()
                .collect(Collectors.groupingBy(DevicePoint::getDeviceId));

        return devices.stream().map(d -> {
            DigitalTwinSceneVO.RadarPose radar = new DigitalTwinSceneVO.RadarPose();
            radar.setDeviceId(d.getId());
            radar.setCode(d.getCode());
            radar.setName(d.getName());
            radar.setLongitude(d.getLongitude());
            radar.setLatitude(d.getLatitude());
            radar.setAltitude(d.getAltitude());
            radar.setHeadingDegrees(value(d.getHeadingDegrees(), BigDecimal.ZERO));
            radar.setPitchDegrees(value(d.getPitchDegrees(), BigDecimal.ZERO));
            radar.setDetectionRangeM(value(d.getDetectionRangeM(), new BigDecimal("250")));
            radar.setHalfAngleDegrees(value(d.getHalfAngleDegrees(), new BigDecimal("25")));
            radar.setAntennaHeightM(value(d.getAntennaHeightM(), new BigDecimal("8.8")));
            radar.setVerticalHalfAngleDegrees(value(d.getVerticalHalfAngleDegrees(), new BigDecimal("15")));
            radar.setStatus(DeviceStatusPolicy.statusOf(d));
            radar.setTargets(byDevice.getOrDefault(d.getId(), List.of()).stream()
                    .map(binding -> targetOf(binding, points.get(binding.getPointId())))
                    .filter(java.util.Objects::nonNull)
                    .toList());
            return radar;
        }).toList();
    }

    private static DigitalTwinSceneVO.RadarTargetPose targetOf(DevicePoint binding, MonitorPoint point) {
        if (point == null) return null;
        return new DigitalTwinSceneVO.RadarTargetPose(
                binding.getId(), point.getId(), point.getCode(), point.getName(),
                point.getLongitude(), point.getLatitude(), point.getAltitude(),
                binding.getTargetCode(), binding.getAzimuthDegrees(), binding.getElevationDegrees(),
                binding.getSlantRangeM(), binding.getReflectorHeightM(),
                Boolean.TRUE.equals(binding.getLineOfSight()), binding.getMinimumClearanceM(),
                binding.getCalibrationStatus());
    }

    private static DigitalTwinSceneVO toVO(DigitalTwinScene s) {
        return new DigitalTwinSceneVO(
                s.getId(), s.getProjectId(), Boolean.TRUE.equals(s.getEnabled()), s.getAssetType(),
                s.getCoordinateMode(), s.getAssetUrl(), s.getAssetVersion(), s.getAssetSha256(),
                s.getAnchorLongitude(), s.getAnchorLatitude(), s.getAnchorHeight(),
                s.getHeadingDegrees(), s.getPitchDegrees(), s.getRollDegrees(), s.getModelScale(),
                s.getCameraHeadingDegrees(), s.getCameraPitchDegrees(), s.getCameraRange(),
                s.getMaximumScreenError(), s.getMaximumMemoryMb(), s.getMaxHeatPoints(), s.getLabelDistance(),
                List.of());
    }

    private static void copy(DigitalTwinSceneRequest r, DigitalTwinScene s) {
        s.setEnabled(r.getEnabled() == null || r.getEnabled());
        s.setAssetType(upper(r.getAssetType()));
        s.setCoordinateMode(upper(r.getCoordinateMode()));
        s.setAssetUrl(r.getAssetUrl().trim());
        s.setAssetVersion(r.getAssetVersion().trim());
        s.setAssetSha256(blankToNull(r.getAssetSha256()));
        s.setAnchorLongitude(r.getAnchorLongitude());
        s.setAnchorLatitude(r.getAnchorLatitude());
        s.setAnchorHeight(value(r.getAnchorHeight(), BigDecimal.ZERO));
        s.setHeadingDegrees(value(r.getHeadingDegrees(), BigDecimal.ZERO));
        s.setPitchDegrees(value(r.getPitchDegrees(), BigDecimal.ZERO));
        s.setRollDegrees(value(r.getRollDegrees(), BigDecimal.ZERO));
        s.setModelScale(value(r.getModelScale(), BigDecimal.ONE));
        s.setCameraHeadingDegrees(value(r.getCameraHeadingDegrees(), BigDecimal.ZERO));
        s.setCameraPitchDegrees(value(r.getCameraPitchDegrees(), new BigDecimal("-35")));
        s.setCameraRange(value(r.getCameraRange(), new BigDecimal("1000")));
        s.setMaximumScreenError(value(r.getMaximumScreenError(), new BigDecimal("16")));
        s.setMaximumMemoryMb(r.getMaximumMemoryMb() == null ? 512 : r.getMaximumMemoryMb());
        s.setMaxHeatPoints(r.getMaxHeatPoints() == null ? 200 : r.getMaxHeatPoints());
        s.setLabelDistance(value(r.getLabelDistance(), new BigDecimal("2000")));
    }

    private static String upper(String s) {
        return s == null ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim().toLowerCase(Locale.ROOT);
    }

    private static <T> T value(T actual, T fallback) {
        return actual == null ? fallback : actual;
    }
}
