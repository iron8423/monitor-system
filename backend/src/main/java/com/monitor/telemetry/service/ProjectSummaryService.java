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
import com.monitor.scope.service.DataScopeService;
import com.monitor.telemetry.dto.ProjectSummaryVO;
import com.monitor.telemetry.entity.Measurement;
import com.monitor.telemetry.mapper.MeasurementMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 项目概览（《B侧接口契约_M0》§3）：一次聚合出驾驶舱 KPI。
 *
 * <p>注意各计数都<b>先判空再查</b>：MyBatis-Plus 的 {@code in(空集合)} 会把条件整条丢掉，
 * 直接查会退化成「全表计数」——项目下无测点时必须短路成 0。</p>
 */
@Service
@RequiredArgsConstructor
public class ProjectSummaryService {

    /**
     * 最大形变取该测项（D1 冻结的点形变主指标）。
     *
     * <p><b>这里是刻意写死的，不是没跟上「测项中立化」。</b>前端可以切主测项、可以加新测项，
     * 但本 KPI 的名字就是 {@code maxDeformationMm}——它答的是「这个项目现在最大变形多少毫米」，
     * 不是「所有测项里绝对值最大的那个数」。两者只在「项目下恰好只有形变测项」时才碰巧相等。</p>
     *
     * <p>那为什么不按测项档案口径化：{@code metric} 表只有 {@code code/name/unit} 三列，
     * <b>没有「量纲类别」这一列</b>，判不出哪些测项算形变。想按 unit 猜的话，
     * 现成的反例就在种子里——{@code defo_mm} 的 unit 是 {@code mm}、{@code rate_mm_d} 的 unit 是
     * {@code mm/d}，任何 {@code contains("mm")} 的写法都会把<b>速率</b>算成形变，
     * 于是「最大形变」被速率顶掉（速率 999 mm/d 与累计 2 mm 完全不是一个量级）。
     * 真要中立化，得先给 {@code metric} 加一列量纲类别并定出归类规则——
     * 在那之前，写死一个明确的 {@code defo_mm} 比猜一个错的更可辩护。</p>
     *
     * <p>契约已写明这一点（{@code docs/B侧接口契约_M0.md} §3）。{@code 03-query.sh} §⑩
     * 把「灌一个大速率不动本字段」钉成了断言，改这里会红。</p>
     */
    private static final String DEFO_METRIC = "defo_mm";

    private final ProjectMapper projectMapper;
    private final SceneMapper sceneMapper;
    private final MonitorObjectMapper objectMapper;
    private final MonitorPointMapper pointMapper;
    private final AlarmMapper alarmMapper;
    private final DeviceMapper deviceMapper;
    private final DevicePointMapper devicePointMapper;
    private final MeasurementMapper measurementMapper;
    private final DataScopeService dataScope;

    public ProjectSummaryVO summary(Long projectId) {
        Project p = projectId == null ? null : projectMapper.selectById(projectId);
        if (p == null) {
            throw new BizException(404, "项目不存在: " + projectId);
        }
        dataScope.assertProjectVisible(projectId);
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

    /**
     * 未解除警情（已确认/处置中/观察中仍算「在办」，只有终态不算）。
     *
     * <p><b>必须把设备告警也算进来。</b>告警有两条来源，挂的东西不一样：{@code POINT} 类型
     * 只写 {@code point_id}，{@code DEVICE} 类型只写 {@code device_id}（另一侧为 NULL，
     * 见 {@code AlarmEngine.trigger} 与 {@code DeviceAlarmMonitor.raise}）。所以只判
     * {@code point_id IN (...)} 是漏的——设备告警那一侧永远是 NULL，而 SQL 里
     * {@code NULL IN (...)} 不成立，于是一条也数不进来，项目下的设备离线告警集体失踪。</p>
     *
     * <p>本端点自己的定义就是「status 不属于 RESOLVED/FALSE_ALARM 的警情」，
     * 没有排除设备告警这一说。</p>
     */
    private long activeAlarmCount(List<Long> pointIds) {
        if (pointIds.isEmpty()) {
            return 0L;
        }
        List<Long> deviceIds = deviceIdsOf(pointIds);
        Long count = alarmMapper.selectCount(new LambdaQueryWrapper<Alarm>()
                .and(w -> {
                    w.in(Alarm::getPointId, pointIds);
                    // 空集合下**绝不能**挂这个 or：MyBatis-Plus 的 in(空集合) 会把条件整条
                    // 丢掉，而外层 and 里兄弟条件还在，于是退化成「所有设备告警都算」——
                    // 比不加这一支还错。项目下没有挂设备时，只剩前半句。
                    if (!deviceIds.isEmpty()) {
                        w.or().in(Alarm::getDeviceId, deviceIds);
                    }
                })
                .notIn(Alarm::getStatus, AlarmConstants.CLOSED));
        return count == null ? 0L : count;
    }

    /**
     * 项目下的设备：测点经 {@code device_point} 反查。一台设备可挂多个测点，故 distinct。
     *
     * <p>空入参必须在这里拦下：{@code in(空集合)} 会把条件整条丢掉，调用方拿到的会是全表设备。</p>
     */
    private List<Long> deviceIdsOf(List<Long> pointIds) {
        if (pointIds.isEmpty()) {
            return List.of();
        }
        return devicePointMapper.selectList(new LambdaQueryWrapper<DevicePoint>()
                        .in(DevicePoint::getPointId, pointIds))
                .stream().map(DevicePoint::getDeviceId).distinct().toList();
    }

    /** 只认状态串为 ONLINE 的设备：标记 FAULT 的即使仍在报数也不计入「在线」。 */
    private long onlineDeviceCount(List<Long> pointIds) {
        List<Long> deviceIds = deviceIdsOf(pointIds);
        if (deviceIds.isEmpty()) {
            return 0L;
        }
        return deviceMapper.selectByIds(deviceIds).stream()
                .filter(d -> "ONLINE".equals(DeviceStatusPolicy.statusOf(d)))
                .count();
    }

    /**
     * 各测点取最新一行 {@link #DEFO_METRIC}，再取绝对值最大者（负向形变同样算「最大形变」）。
     * 逐点带 LIMIT 1，扫描量与数据总量无关，只与测点数成正比。
     *
     * <p>只扫 {@code defo_mm} 一行是有意的——理由见 {@link #DEFO_METRIC} 的注释。</p>
     *
     * <p>「最新一行」的判据走 {@link MeasurementMapper#latestRowOf}，与
     * {@link MeasurementQueryService#latest} 是同一个方法——两处若各写一份排序，
     * 同刻多行时 {@code /points/{id}/latest} 与本端点的最大形变会取到不同行。
     * 本方法此前正是漏了 id 兜底的那一处。</p>
     */
    private Double maxDeformation(List<Long> pointIds) {
        // 上界取 now()：未来采集时间的行不能成为「当前值」（清单第 10 条）。
        // 这里是项目概览 KPI，被钉住的后果是一个永远不变的最大形变数字。
        LocalDateTime ceiling = LocalDateTime.now();
        Double max = null;
        for (Long pointId : pointIds) {
            Measurement last = measurementMapper.selectOne(
                    measurementMapper.latestRowOf(pointId, DEFO_METRIC, ceiling).last("LIMIT 1"));
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
