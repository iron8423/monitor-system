package com.monitor.asset.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.asset.DeviceStatusPolicy;
import com.monitor.asset.dto.DeviceStatusVO;
import com.monitor.asset.entity.Device;
import com.monitor.asset.entity.DevicePoint;
import com.monitor.asset.mapper.DeviceMapper;
import com.monitor.asset.mapper.DevicePointMapper;
import com.monitor.audit.annotation.AuditAction;
import com.monitor.common.Result;
import com.monitor.common.base.BaseCrudController;
import com.monitor.common.exception.BizException;
import com.monitor.common.util.Times;
import com.monitor.project.mapper.MonitorPointMapper;
import com.monitor.scope.service.DataScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设备档案：CRUD + 状态推导 + 设备-测点绑定。
 */
@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
@SuppressWarnings("null")
public class DeviceController extends BaseCrudController<Device> {

    private final DeviceMapper deviceMapper;
    private final DevicePointMapper devicePointMapper;
    private final MonitorPointMapper pointMapper;
    private final DataScopeService dataScope;

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
    public Result<List<Device>> list() {
        List<Device> devices = deviceMapper.selectList(scopeFilter());
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
        devicePointMapper.insert(dp);
        return Result.ok();
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
    private void requireDevice(Long id) {
        if (id == null || deviceMapper.selectById(id) == null) {
            throw new BizException(404, "设备不存在: " + id);
        }
        dataScope.assertDeviceVisible(id);
    }

    private void requirePoint(Long id) {
        if (id == null || pointMapper.selectById(id) == null) {
            throw new BizException(404, "测点不存在: " + id);
        }
    }
}
