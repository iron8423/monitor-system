package com.monitor.asset.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.asset.dto.DeviceStatusVO;
import com.monitor.asset.entity.Device;
import com.monitor.asset.entity.DevicePoint;
import com.monitor.asset.mapper.DeviceMapper;
import com.monitor.asset.mapper.DevicePointMapper;
import com.monitor.audit.annotation.AuditAction;
import com.monitor.common.Result;
import com.monitor.common.base.BaseCrudController;
import com.monitor.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 设备档案：CRUD + 状态推导 + 设备-测点绑定。
 */
@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
@SuppressWarnings("null")
public class DeviceController extends BaseCrudController<Device> {

    /** 超过该时长（分钟）未上报视为离线 */
    private static final int OFFLINE_MINUTES = 5;
    private static final BigDecimal LOW_BATTERY_THRESHOLD = new BigDecimal("20");

    private final DeviceMapper deviceMapper;
    private final DevicePointMapper devicePointMapper;

    @Override
    protected BaseMapper<Device> mapper() {
        return deviceMapper;
    }

    /** 设备状态：在线/离线由最近上报时间推导，低电量按阈值判断。 */
    @GetMapping("/{id}/status")
    public Result<DeviceStatusVO> status(@PathVariable Long id) {
        Device d = deviceMapper.selectById(id);
        if (d == null) {
            throw new BizException(404, "设备不存在: " + id);
        }
        boolean online = d.getLastReportTime() != null
                && d.getLastReportTime().isAfter(LocalDateTime.now().minusMinutes(OFFLINE_MINUTES));
        boolean lowBattery = d.getBattery() != null
                && d.getBattery().compareTo(LOW_BATTERY_THRESHOLD) < 0;
        String status = "FAULT".equals(d.getStatus()) ? "FAULT" : (online ? "ONLINE" : "OFFLINE");

        DeviceStatusVO vo = new DeviceStatusVO();
        vo.setDeviceId(d.getId());
        vo.setCode(d.getCode());
        vo.setStatus(status);
        vo.setOnline(online);
        vo.setBattery(d.getBattery());
        vo.setLowBattery(lowBattery);
        vo.setLastReportTime(d.getLastReportTime());
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
