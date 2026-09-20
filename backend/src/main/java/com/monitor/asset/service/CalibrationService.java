package com.monitor.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.monitor.asset.RadarCoveragePolicy;
import com.monitor.asset.entity.Device;
import com.monitor.asset.entity.DevicePoint;
import com.monitor.asset.mapper.DeviceMapper;
import com.monitor.asset.mapper.DevicePointMapper;
import com.monitor.common.exception.BizException;
import com.monitor.project.entity.MonitorPoint;
import com.monitor.project.mapper.MonitorPointMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 标定的生命周期：位姿变更 -> 旧标定失效 -> 重新标定（清单第 09 条）。
 *
 * <p><b>为什么是一个服务而不是塞进 Controller</b>：失效判定要在「读出旧档案」与「写回新档案」
 * 之间做，这中间必须有事务，而本仓 0 处 Controller 上有 {@code @Transactional}。
 * 逻辑放在这里，两个 Controller 各覆盖一次 {@code update} 委托进来，
 * 基类 {@code BaseCrudController} 一行不动——它下面挂着 7 个子类，
 * 在那里加模板钩子会波及全部写路径。</p>
 *
 * <p><b>为什么不在 mapper / MyBatis-Plus 拦截器上做</b>：{@code IngestService} 每一批上报都会
 * {@code deviceMapper.updateById(device)}（几何字段全是实际值），拦截器分不清「谁在写」——
 * 那等于每批上报都把全部雷达的标定打失效。失效必须挂在**档案维护**这条路径上，
 * 而不是「任何一次对 device 表的写」上。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CalibrationService {

    /** 失效原因（短代码，进 {@code device_point.invalidated_reason}）。 */
    public static final String REASON_DEVICE_POSE_CHANGED = "DEVICE_POSE_CHANGED";
    public static final String REASON_POINT_MOVED = "POINT_MOVED";
    public static final String REASON_MANUAL = "MANUAL";

    /**
     * 人工停用允许的原因码白名单。不认识的 -> 400。
     *
     * <p>不做成自由文本：这一列会被前端当枚举渲染、被运维 grep，写进任意串之后
     * 「按原因统计」这类查询就废了。将来要加原因，在这里加一个常量。</p>
     */
    private static final Set<String> MANUAL_REASONS = Set.of(
            REASON_MANUAL, "DEVICE_RELOCATED", "TARGET_REMOVED", "MAINTENANCE", "SUSPECTED_DRIFT");

    private final DeviceMapper deviceMapper;
    private final DevicePointMapper devicePointMapper;
    private final MonitorPointMapper monitorPointMapper;

    /**
     * 更新设备档案，位姿变了就把这台设备上生效中的标定全部置为失效。
     *
     * <p><b>先置失效，再写档案，是刻意的。</b>两者都在 {@code @Transactional} 里，
     * 正常路径上是一次原子提交；顺序只为了一件事——万一将来有人去掉那个注解，
     * 中途失败时留下的是哪一边：</p>
     * <ul>
     *   <li>本实现（先失效）：留下「标定已失效、档案还是旧的」= <b>过度失效</b>，
     *       重新标定一次即可恢复，而且症状是**显式**的（界面直接显示已失效）。</li>
     *   <li>反过来（先档案）：留下「档案已挪、标定还 ACTIVE」= <b>失效不足</b>，
     *       正是本条要消灭的那个状态，而且完全静默。</li>
     * </ul>
     * <p>两个失败模式里选轻的那个。这条注释别删——顺序看起来可以随便调，其实不行。</p>
     *
     * <p>返回重新查库后的档案（{@code updateById} 不回填实体），调用方直接返回它，
     * HTTP 响应形状与基类的 {@code Result<Device>} 一致：验收套件断言的就是这个 body。</p>
     */
    @Transactional
    public DeviceUpdateOutcome updateDevice(Long id, Device after, String operator) {
        Device before = deviceMapper.selectById(id);
        after.setId(id);
        if (before == null) {
            // 保持基类今天的行为：不存在时 updateById 影响 0 行、selectById 返回 null，
            // 响应是 200 + data:null（不是 404）。这里不「顺手改成 404」——
            // 那是一次没被要求的语义变更。
            deviceMapper.updateById(after);
            return new DeviceUpdateOutcome(null, 0);
        }
        int invalidated = 0;
        if (RadarCoveragePolicy.poseChanged(before, after)) {
            invalidated = devicePointMapper.invalidateActiveOfDevice(id, RadarCoveragePolicy.INVALID,
                    REASON_DEVICE_POSE_CHANGED, LocalDateTime.now(), operator);
            log.info("设备几何变更使标定失效 deviceId={} code={} invalidated={} reason={} operator={}",
                    id, before.getCode(), invalidated, REASON_DEVICE_POSE_CHANGED, operator);
        }
        deviceMapper.updateById(after);
        return new DeviceUpdateOutcome(deviceMapper.selectById(id), invalidated);
    }

    /** 测点档案更新。测点被移动时，失效所有观测它的标定（可能来自多台雷达）。 */
    @Transactional
    public PointUpdateOutcome updatePoint(Long id, MonitorPoint after, String operator) {
        MonitorPoint before = monitorPointMapper.selectById(id);
        after.setId(id);
        if (before == null) {
            monitorPointMapper.updateById(after);
            return new PointUpdateOutcome(null, 0);
        }
        int invalidated = 0;
        if (RadarCoveragePolicy.pointMoved(before, after)) {
            invalidated = devicePointMapper.invalidateActiveOfPoint(id, RadarCoveragePolicy.INVALID,
                    REASON_POINT_MOVED, LocalDateTime.now(), operator);
            log.info("测点位置变更使标定失效 pointId={} code={} invalidated={} reason={} operator={}",
                    id, before.getCode(), invalidated, REASON_POINT_MOVED, operator);
        }
        monitorPointMapper.updateById(after);
        return new PointUpdateOutcome(monitorPointMapper.selectById(id), invalidated);
    }

    /**
     * 人工停用一条标定：**保留绑定关系与标定参数**，只把状态置为 INVALID。
     *
     * <p>与相邻的 {@code DELETE /devices/{id}/points/{pointId}}（解绑）是完全相反的操作——
     * 那个是硬删行、标定参数一起丢（{@code device_point} 无软删列）。</p>
     *
     * <p>幂等：已经 INVALID 时 {@code invalidateOne} 影响 0 行，返回当前状态、不再改写痕迹
     * ——否则「谁在什么时候停用的」会变成最后一次点按钮的人和时间。</p>
     */
    @Transactional
    public DevicePoint invalidateCalibration(Long deviceId, Long pointId, String reason, String operator) {
        String normalized = reason == null || reason.isBlank() ? REASON_MANUAL : reason.trim().toUpperCase();
        if (!MANUAL_REASONS.contains(normalized)) {
            throw new BizException(400, "不支持的人工失效原因: " + reason);
        }
        DevicePoint binding = devicePointMapper.selectOne(new LambdaQueryWrapper<DevicePoint>()
                .eq(DevicePoint::getDeviceId, deviceId)
                .eq(DevicePoint::getPointId, pointId)
                .last("LIMIT 1"));
        if (binding == null) {
            throw new BizException(404, "设备与测点尚未绑定");
        }
        int changed = devicePointMapper.invalidateOne(deviceId, pointId, RadarCoveragePolicy.INVALID,
                normalized, LocalDateTime.now(), operator);
        if (changed == 0) {
            return binding;
        }
        log.info("标定被人工停用 deviceId={} pointId={} reason={} operator={}",
                deviceId, pointId, normalized, operator);
        return devicePointMapper.selectById(binding.getId());
    }

    /** 设备更新的结果：失效后的档案 + 这次连带失效了几条标定。 */
    public record DeviceUpdateOutcome(Device device, int invalidated) {
    }

    /** 测点更新的结果。 */
    public record PointUpdateOutcome(MonitorPoint point, int invalidated) {
    }
}
