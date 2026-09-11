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

    @Override
    protected BaseMapper<Device> mapper() {
        return deviceMapper;
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
        List<Device> devices = deviceMapper.selectList(null);
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
        return Result.ok(devicePointMapper.selectList(
                new LambdaQueryWrapper<DevicePoint>().eq(DevicePoint::getDeviceId, id)));
    }

    @PostMapping("/{id}/points/{pointId}")
    @PreAuthorize("hasAnyRole('ADMIN','MAINTAINER')")
    @AuditAction(action = "绑定测点")
    public Result<Void> bind(@PathVariable Long id, @PathVariable Long pointId) {
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
        devicePointMapper.delete(new LambdaQueryWrapper<DevicePoint>()
                .eq(DevicePoint::getDeviceId, id).eq(DevicePoint::getPointId, pointId));
        return Result.ok();
    }
}
