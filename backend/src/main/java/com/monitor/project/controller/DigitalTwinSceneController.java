package com.monitor.project.controller;

import com.monitor.audit.annotation.AuditAction;
import com.monitor.common.Result;
import com.monitor.project.dto.DigitalTwinSceneRequest;
import com.monitor.project.dto.DigitalTwinSceneVO;
import com.monitor.project.service.DigitalTwinSceneService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 每个项目的数字孪生资产、配准参数、性能预算和雷达姿态接口。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/digital-twin")
@RequiredArgsConstructor
public class DigitalTwinSceneController {

    private final DigitalTwinSceneService service;

    @GetMapping
    public Result<DigitalTwinSceneVO> get(@PathVariable Long projectId) {
        return Result.ok(service.get(projectId));
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    @AuditAction(action = "更新数字孪生配置")
    public Result<DigitalTwinSceneVO> upsert(@PathVariable Long projectId,
                                             @Valid @RequestBody DigitalTwinSceneRequest request) {
        return Result.ok(service.upsert(projectId, request));
    }

    @DeleteMapping
    @PreAuthorize("hasRole('ADMIN')")
    @AuditAction(action = "停用数字孪生配置")
    public Result<Void> disable(@PathVariable Long projectId) {
        service.disable(projectId);
        return Result.ok();
    }
}

