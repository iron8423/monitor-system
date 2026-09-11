package com.monitor.telemetry.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.monitor.alarm.AlarmConstants;
import com.monitor.alarm.entity.Alarm;
import com.monitor.alarm.mapper.AlarmMapper;
import com.monitor.asset.DeviceStatusPolicy;
import com.monitor.asset.entity.DevicePoint;
import com.monitor.asset.mapper.DeviceMapper;
import com.monitor.asset.mapper.DevicePointMapper;
import com.monitor.common.exception.BizException;
import com.monitor.project.entity.MonitorObject;
import com.monitor.project.entity.MonitorPoint;
import com.monitor.project.entity.Project;
import com.monitor.project.entity.Scene;
import com.monitor.project.mapper.MonitorObjectMapper;
import com.monitor.project.mapper.MonitorPointMapper;
import com.monitor.project.mapper.ProjectMapper;
import com.monitor.project.mapper.SceneMapper;
import com.monitor.telemetry.dto.ProjectSummaryVO;
import com.monitor.telemetry.entity.Measurement;
import com.monitor.telemetry.mapper.MeasurementMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 项目概览（《B侧接口契约_M0》§3）：一次聚合出驾驶舱 KPI。
 *
 * <p>注意各计数都<b>先判空再查</b>：MyBatis-Plus 的 {@code in(空集合)} 会把条件整条丢掉，
 * 直接查会退化成「全表计数」——项目下无测点时必须短路成 0。</p>
 */
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class ProjectSummaryService {

    /** 最大形变取该测项（D1 冻结的点形变主指标）。 */
    private static final String DEFO_METRIC = "defo_mm";

    private final ProjectMapper projectMapper;
    private final SceneMapper sceneMapper;
    private final MonitorObjectMapper objectMapper;
    private final MonitorPointMapper pointMapper;
    private final AlarmMapper alarmMapper;
    private final DeviceMapper deviceMapper;
    private final DevicePointMapper devicePointMapper;
    private final MeasurementMapper measurementMapper;

    public ProjectSummaryVO summary(Long projectId) {
        Project p = projectId == null ? null : projectMapper.selectById(projectId);
        if (p == null) {
            throw new BizException(404, "项目不存在: " + projectId);
        }
        List<Long> pointIds = pointIdsOf(projectId);

        ProjectSummaryVO vo = new ProjectSummaryVO();
        vo.setProjectId(projectId);
        vo.setPointCount(pointIds.size());
        vo.setAlertCount(activeAlarmCount(pointIds));
        vo.setOnlineDeviceCount(onlineDeviceCount(pointIds));
        vo.setMaxDeformationMm(maxDeformation(pointIds));
        return vo;
    }

    /** 项目 → 场景 → 对象 → 测点，逐层收窄。 */
    private List<Long> pointIdsOf(Long projectId) {
        List<Long> sceneIds = sceneMapper.selectList(new LambdaQueryWrapper<Scene>()
                        .eq(Scene::getProjectId, projectId))
                .stream().map(Scene::getId).toList();
        if (sceneIds.isEmpty()) {
            return List.of();
        }
        List<Long> objectIds = objectMapper.selectList(new LambdaQueryWrapper<MonitorObject>()
                        .in(MonitorObject::getSceneId, sceneIds))
                .stream().map(MonitorObject::getId).toList();
        if (objectIds.isEmpty()) {
            return List.of();
        }
        return pointMapper.selectList(new LambdaQueryWrapper<MonitorPoint>()
                        .in(MonitorPoint::getObjectId, objectIds))
                .stream().map(MonitorPoint::getId).toList();
    }

    /** 未解除警情（已确认/处置中/观察中仍算「在办」，只有终态不算）。 */
    private long activeAlarmCount(List<Long> pointIds) {
        if (pointIds.isEmpty()) {
            return 0L;
        }
        Long count = alarmMapper.selectCount(new LambdaQueryWrapper<Alarm>()
                .in(Alarm::getPointId, pointIds)
                .notIn(Alarm::getStatus, AlarmConstants.CLOSED));
        return count == null ? 0L : count;
    }

    /** 只认状态串为 ONLINE 的设备：标记 FAULT 的即使仍在报数也不计入「在线」。 */
    private long onlineDeviceCount(List<Long> pointIds) {
        if (pointIds.isEmpty()) {
            return 0L;
        }
        List<Long> deviceIds = devicePointMapper.selectList(new LambdaQueryWrapper<DevicePoint>()
                        .in(DevicePoint::getPointId, pointIds))
                .stream().map(DevicePoint::getDeviceId).distinct().toList();
        if (deviceIds.isEmpty()) {
            return 0L;
        }
        return deviceMapper.selectByIds(deviceIds).stream()
                .filter(d -> "ONLINE".equals(DeviceStatusPolicy.statusOf(d)))
                .count();
    }

    /**
     * 各测点取最新一行 defo_mm，再取绝对值最大者（负向形变同样算「最大形变」）。
     * 逐点带 LIMIT 1，扫描量与数据总量无关，只与测点数成正比。
     */
    private Double maxDeformation(List<Long> pointIds) {
        Double max = null;
        for (Long pointId : pointIds) {
            Measurement last = measurementMapper.selectOne(new LambdaQueryWrapper<Measurement>()
                    .eq(Measurement::getPointId, pointId)
                    .eq(Measurement::getMetricCode, DEFO_METRIC)
                    .isNotNull(Measurement::getCollectTime)
                    .orderByDesc(Measurement::getCollectTime)
                    .last("LIMIT 1"));
            if (last == null || last.getMeasureValue() == null) {
                continue;
            }
            double v = last.getMeasureValue();
            if (max == null || Math.abs(v) > Math.abs(max)) {
                max = v;
            }
        }
        // 去掉 Double 表示噪声（如 4.199999999999999 -> 4.2），精度对齐 measure_value 列
        return max == null ? null : BigDecimal.valueOf(max).setScale(6, RoundingMode.HALF_UP).doubleValue();
    }
}
