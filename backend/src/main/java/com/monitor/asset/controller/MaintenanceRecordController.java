package com.monitor.asset.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.monitor.asset.entity.MaintenanceRecord;
import com.monitor.asset.mapper.MaintenanceRecordMapper;
import com.monitor.audit.annotation.AuditAction;
import com.monitor.auth.security.SecurityUser;
import com.monitor.common.Result;
import com.monitor.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设备维护记录（运维闭环 D8）：运维员/管理员可创建，operator 自动取当前用户。
 */
@RestController
@RequestMapping("/api/v1/maintenance-records")
@RequiredArgsConstructor
@SuppressWarnings("null")
public class MaintenanceRecordController {

    private final MaintenanceRecordMapper maintenanceRecordMapper;

    @GetMapping
    public Result<List<MaintenanceRecord>> list(@RequestParam(required = false) Long deviceId) {
        LambdaQueryWrapper<MaintenanceRecord> qw = new LambdaQueryWrapper<>();
        if (deviceId != null) {
            qw.eq(MaintenanceRecord::getDeviceId, deviceId);
        }
        qw.orderByDesc(MaintenanceRecord::getCreatedAt);
        return Result.ok(maintenanceRecordMapper.selectList(qw));
    }

    @GetMapping("/{id}")
    public Result<MaintenanceRecord> get(@PathVariable Long id) {
        MaintenanceRecord r = maintenanceRecordMapper.selectById(id);
        if (r == null) {
            throw new BizException(404, "维护记录不存在: " + id);
        }
        return Result.ok(r);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','MAINTAINER')")
    @AuditAction(action = "创建维护记录")
    public Result<MaintenanceRecord> create(@RequestBody MaintenanceRecord record,
                                            @AuthenticationPrincipal SecurityUser currentUser) {
        record.setId(null);
        record.setOperator(currentUser.getUsername());
        maintenanceRecordMapper.insert(record);
        return Result.ok(record);
    }
}
