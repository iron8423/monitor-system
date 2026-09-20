package com.monitor.audit.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.monitor.audit.entity.AuditLog;
import com.monitor.audit.mapper.AuditLogMapper;
import com.monitor.common.PageResult;
import com.monitor.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计日志查询（仅管理员可见）。
 */
@RestController
@RequestMapping("/api/v1/audit-logs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AuditLogController {

    private final AuditLogMapper auditLogMapper;

    @GetMapping
    public Result<PageResult<AuditLog>> list(@RequestParam(defaultValue = "1") long pageNum,
                                             @RequestParam(defaultValue = "20") long pageSize,
                                             @RequestParam(required = false) String username,
                                             @RequestParam(required = false) String targetType) {
        LambdaQueryWrapper<AuditLog> qw = new LambdaQueryWrapper<>();
        if (username != null && !username.isBlank()) {
            qw.eq(AuditLog::getUsername, username);
        }
        if (targetType != null && !targetType.isBlank()) {
            qw.eq(AuditLog::getTargetType, targetType);
        }
        qw.orderByDesc(AuditLog::getCreatedAt);
        Page<AuditLog> page = auditLogMapper.selectPage(new Page<>(pageNum, pageSize), qw);
        return Result.ok(PageResult.of(page));
    }
}
