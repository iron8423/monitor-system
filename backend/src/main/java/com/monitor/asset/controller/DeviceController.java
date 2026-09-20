package com.monitor.asset.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.asset.DeviceStatusPolicy;
import com.monitor.asset.RadarCoveragePolicy;
import com.monitor.asset.dto.DevicePointCalibrationRequest;
import com.monitor.asset.dto.DeviceStatusVO;
import com.monitor.asset.entity.Device;
import com.monitor.asset.entity.DevicePoint;
import com.monitor.asset.mapper.DeviceMapper;
import com.monitor.asset.mapper.DevicePointMapper;
import com.monitor.asset.service.CalibrationService;
import com.monitor.audit.annotation.AuditAction;
import com.monitor.auth.security.SecurityUser;
import com.monitor.common.Result;
import com.monitor.common.base.BaseCrudController;
import com.monitor.common.exception.BizException;
import com.monitor.common.util.Times;
import com.monitor.project.mapper.MonitorPointMapper;
import com.monitor.scope.service.DataScopeService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.time.LocalDateTime;

/**
 * 设备档案：CRUD + 状态推导 + 设备-测点绑定 + 标定失效（清单第 09 条）。
 */
@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
public class DeviceController extends BaseCrudController<Device> {

    private final DeviceMapper deviceMapper;
    private final DevicePointMapper devicePointMapper;
    private final MonitorPointMapper pointMapper;
    private final DataScopeService dataScope;
    private final CalibrationService calibrationService;

    @Override
    protected BaseMapper<Device> mapper() {
        return deviceMapper;
    }

    /** 设备本身不挂项目，归属由 {@code device_point} 反查测点决定；未绑定的设备只对 ADMIN 可见。 */
    @Override
    protected LambdaQueryWrapper<Device> scopeFilter() {
        return dataScope.deviceFilter();
    }

    @Override
    protected boolean inScope(Device entity) {
        return dataScope.canSeeDevice(entity.getId());
    }

    /**
     * 列表：返回**推导后**的 status，而不是档案表里的存量值。
     *
     * <p>为什么必须覆盖：{@code device.status} 这一列的语义是「人工显式标注」（只有 FAULT
     * 有意义，见 {@link DeviceStatusPolicy#statusOf}），但种子里写进去的是 {@code ONLINE}。
     * 基类的通用列表直接吐实体，于是同一个设备会出现两个相反的结论——
     * 列表说「在线」，而 {@code /devices/{id}/status} 与项目概览的在线设备数说「离线」，
     * 因为后两者是按 lastReportTime 现算的。</p>
     *
     * <p>前端无法调和这种矛盾：要么把 5 分钟判据在浏览器里抄一遍（判据就分叉了，
     * 而告警是按后端判据发的），要么按设备数逐个去调 {@code /{id}/status}（N+1）。
     * 所以修在这里——列表端点本就该给出算好的状态。</p>
     *
     * <p>注意只改返回的对象、不回写：这是读时推导，不是把推导结果持久化。</p>
     */
    @Override
    @GetMapping
    public Result<List<Device>> list(@RequestParam(required = false) Long limit,
                                     HttpServletResponse response) {
        // 上限与 X-Result-Truncated 的判定都在基类（P1-3）；这里只补"读时推导状态"
        List<Device> devices = listCapped(limit, response);
        devices.forEach(DeviceController::fillDerivedStatus);
        return Result.ok(devices);
    }

    /** 详情同样给推导值，免得列表与详情又是两个口径。 */
    @Override
    @GetMapping("/{id}")
    public Result<Device> get(@PathVariable Long id) {
        Device d = deviceMapper.selectById(id);
        if (d == null) {
            throw new BizException(404, "设备不存在: " + id);
        }
        if (!inScope(d)) {
            throw new BizException(403, "无权访问该记录: " + id);
        }
        fillDerivedStatus(d);
        return Result.ok(d);
    }

    private static void fillDerivedStatus(Device d) {
        d.setStatus(DeviceStatusPolicy.statusOf(d));
    }

    /**
     * 更新设备档案（清单第 09 条）。位姿一变，这台设备上生效中的标定随之失效——
     * 旧标定的方位/斜距是按**旧位置**量出来的，继续挂 ACTIVE 就是在说一句已经不成立的话。
     *
     * <p><b>覆盖时三个注解必须逐字重声明。</b>Java 的方法注解不继承，而 Spring 的
     * {@code @annotation} 切点匹配**最具体**的那个方法：漏掉
     * {@code @PreAuthorize("hasRole('ADMIN')")} 会把一个 ADMIN-only 端点悄悄变成
     * 「任何已登录用户可写」，漏掉 {@code @AuditAction} 会静默丢掉审计。
     * 先例是上面覆盖 {@code list}/{@code get} 时重声明 {@code @GetMapping}。</p>
     *
     * <p>签名必须与基类**逐字一致**（两参）。加一个 {@code @AuthenticationPrincipal} 参数
     * 看着更符合 Controller 的房内写法（{@code AlarmController} 等就是这么取的），
     * 但那会变成**重载**而不是覆盖：基类那个两参版仍然带着 {@code @PutMapping("/{id}")}
     * 被注册，启动时直接 ambiguous mapping 报错。所以操作者在这里从
     * {@code SecurityContextHolder} 取——与 {@code AuditAspect.currentUser()} 同一个来源。</p>
     */
    @Override
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @AuditAction(action = "更新")
    public Result<Device> update(@PathVariable Long id, @Valid @RequestBody Device entity) {
        return Result.ok(calibrationService.updateDevice(id, entity, currentUsername()).device());
    }

    /** 当前登录用户名，取法与 {@code AuditAspect.currentUser()} 一致；无上下文时返回 null。 */
    private static String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof SecurityUser su ? su.getUsername() : null;
    }

    /** 设备状态：在线/离线由最近上报时间推导，低电量按阈值判断（口径见 {@link DeviceStatusPolicy}）。 */
    @GetMapping("/{id}/status")
    public Result<DeviceStatusVO> status(@PathVariable Long id) {
        Device d = deviceMapper.selectById(id);
        if (d == null) {
            throw new BizException(404, "设备不存在: " + id);
        }
        dataScope.assertDeviceVisible(id);
        boolean online = DeviceStatusPolicy.isOnline(d.getLastReportTime());
        boolean lowBattery = DeviceStatusPolicy.isLowBattery(d.getBattery());
        String status = DeviceStatusPolicy.statusOf(d);

        DeviceStatusVO vo = new DeviceStatusVO();
        vo.setDeviceId(d.getId());
        vo.setCode(d.getCode());
        vo.setStatus(status);
        vo.setOnline(online);
        vo.setBattery(d.getBattery());
        vo.setLowBattery(lowBattery);
        vo.setLastReportTime(Times.iso(d.getLastReportTime()));
        return Result.ok(vo);
    }

    @GetMapping("/{id}/points")
    public Result<List<DevicePoint>> boundPoints(@PathVariable Long id) {
        dataScope.assertDeviceVisible(id);
        return Result.ok(devicePointMapper.selectList(
                new LambdaQueryWrapper<DevicePoint>().eq(DevicePoint::getDeviceId, id)));
    }

    /**
     * 绑定测点。两侧都要过范围：设备是可操作对象，测点是这次改动要落到的数据——
     * 只判设备的话，一个 MAINTAINER 能把项目 B 的测点挂到本项目的设备上，
     * 那条绑定随后会让这台设备对项目 B 的成员也可见（设备的可见性正是由绑定反推的）。
     */
    @PostMapping("/{id}/points/{pointId}")
    @PreAuthorize("hasAnyRole('ADMIN','MAINTAINER')")
    @AuditAction(action = "绑定测点")
    public Result<Void> bind(@PathVariable Long id, @PathVariable Long pointId) {
        requireDevice(id);
        requirePoint(pointId);
        dataScope.assertPointVisible(pointId);
        Long count = devicePointMapper.selectCount(new LambdaQueryWrapper<DevicePoint>()
                .eq(DevicePoint::getDeviceId, id).eq(DevicePoint::getPointId, pointId));
        if (count != null && count > 0) {
            throw new BizException("设备与测点已绑定");
        }
        DevicePoint dp = new DevicePoint();
        dp.setDeviceId(id);
        dp.setPointId(pointId);
        dp.setCalibrationStatus(RadarCoveragePolicy.PENDING);
        dp.setLineOfSight(false);
        devicePointMapper.insert(dp);
        return Result.ok();
    }

    /**
     * 激活雷达目标标定。简单“绑定”只会得到 PENDING 关系，不能接收生产测值；
     * 必须补齐方位、俯仰、斜距和视线结果，并通过设备量程/FOV 校验后才转为 ACTIVE。
     */
    @PutMapping("/{id}/points/{pointId}/calibration")
    @PreAuthorize("hasAnyRole('ADMIN','MAINTAINER')")
    @AuditAction(action = "标定雷达测点")
    public Result<DevicePoint> calibrate(@PathVariable Long id,
                                         @PathVariable Long pointId,
                                         @Valid @RequestBody DevicePointCalibrationRequest request) {
        Device device = requireDevice(id);
        requirePoint(pointId);
        dataScope.assertPointVisible(pointId);
        DevicePoint binding = devicePointMapper.selectOne(new LambdaQueryWrapper<DevicePoint>()
                .eq(DevicePoint::getDeviceId, id)
                .eq(DevicePoint::getPointId, pointId)
                .last("LIMIT 1"));
        if (binding == null) {
            throw new BizException(404, "设备与测点尚未绑定");
        }
        // 一个 now 贯穿校验与三处赋值：分三次调 now() 会让「刚标定完就已过期」这类
        // 边界结论取决于这几行之间过了多久。
        LocalDateTime now = LocalDateTime.now();
        RadarCoveragePolicy.validateCalibration(device, request, now);
        binding.setTargetCode(request.getTargetCode().trim());
        binding.setAzimuthDegrees(request.getAzimuthDegrees());
        binding.setElevationDegrees(request.getElevationDegrees());
        binding.setSlantRangeM(request.getSlantRangeM());
        binding.setReflectorHeightM(request.getReflectorHeightM());
        binding.setLineOfSight(true);
        binding.setMinimumClearanceM(request.getMinimumClearanceM());
        binding.setCalibrationStatus(RadarCoveragePolicy.ACTIVE);
        binding.setCalibratedAt(now);
        binding.setValidFrom(request.getValidFrom() == null ? now : request.getValidFrom());
        binding.setValidTo(request.getValidTo());
        binding.setCalibrationNote(request.getNote());
        // 显式 SQL 而不是 updateById：后者跳过 null 字段，于是 valid_to 传 null 表意
        // 「永久有效」根本写不进去（只能沿用上一次的有效期），失效痕迹也清不掉。
        // 详见 DevicePointMapper#applyCalibration。
        devicePointMapper.applyCalibration(binding);
        // 回读再返回，和基类 update 的做法一致。直接返回上面这个 binding 会撒一个
        // 自相矛盾的谎：它是失效之前的快照，`applyCalibration` 刚刚在 SQL 里把
        // invalidated_at/reason 清成 NULL，而这个对象里还留着旧值——于是「重新标定成功」
        // 的响应体是 `calibrationStatus=ACTIVE` 外加一个 `invalidatedAt`。
        // 一把 INVALID 的标定重新标定就会复现。
        return Result.ok(devicePointMapper.selectById(binding.getId()));
    }

    /**
     * 人工停用一条标定（清单第 09 条）：保留绑定关系与标定参数，只把状态置为 INVALID。
     *
     * <p><b>注意与相邻端点的区别</b>：{@code DELETE /{id}/points/{pointId}} 是**解绑**，
     * {@code device_point} 无软删列，那是**硬删行**——方位、斜距、视线结论一起丢。
     * 本端点只改状态，标定参数留着，所以「停用错了」是可以重新标定回来的。
     * 两个 DELETE 只差一段路径、破坏性完全相反，别走错。</p>
     *
     * <p>角色与 {@code bind}/{@code calibrate}/{@code unbind} 完全一致。这里有一处
     * **刻意的不对称**：自动失效挂在 {@code PUT /devices/{id}} 上（ADMIN-only，与档案写同权），
     * 手动停用则和它的兄弟端点一样含 MAINTAINER——「能激活的就能停用」。
     * 看着像漏了，其实不是。</p>
     */
    @DeleteMapping("/{id}/points/{pointId}/calibration")
    @PreAuthorize("hasAnyRole('ADMIN','MAINTAINER')")
    @AuditAction(action = "停用标定")
    public Result<DevicePoint> invalidateCalibration(@PathVariable Long id,
                                                     @PathVariable Long pointId,
                                                     @RequestParam(required = false) String reason) {
        requireDevice(id);
        requirePoint(pointId);
        dataScope.assertPointVisible(pointId);
        return Result.ok(calibrationService.invalidateCalibration(id, pointId, reason, currentUsername()));
    }

    @DeleteMapping("/{id}/points/{pointId}")
    @PreAuthorize("hasAnyRole('ADMIN','MAINTAINER')")
    @AuditAction(action = "解绑测点")
    public Result<Void> unbind(@PathVariable Long id, @PathVariable Long pointId) {
        requireDevice(id);
        requirePoint(pointId);
        dataScope.assertPointVisible(pointId);
        devicePointMapper.delete(new LambdaQueryWrapper<DevicePoint>()
                .eq(DevicePoint::getDeviceId, id).eq(DevicePoint::getPointId, pointId));
        return Result.ok();
    }

    /**
     * 存在性检查（404）。此前两个绑定端点都没有——{@code POST /devices/9999/points/9999}
     * 会**静默插一条悬空绑定**，指向一个不存在的设备与测点。
     *
     * <p>判成 404 而不是让它落到 403：不存在与「存在但不在你的范围内」是两件事，
     * 混成一个状态码之后，「为什么我绑不上」会变得没法排查。</p>
     */
    private Device requireDevice(Long id) {
        Device device = id == null ? null : deviceMapper.selectById(id);
        if (device == null) {
            throw new BizException(404, "设备不存在: " + id);
        }
        dataScope.assertDeviceVisible(id);
        return device;
    }

    private void requirePoint(Long id) {
        if (id == null || pointMapper.selectById(id) == null) {
            throw new BizException(404, "测点不存在: " + id);
        }
    }
}
