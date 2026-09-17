package com.monitor.telemetry.web;

import com.monitor.common.Result;
import com.monitor.telemetry.dto.PointLatestVO;
import com.monitor.telemetry.service.MeasurementQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 大屏项目快照批量端点，避免 1000 测点首屏产生 1000 个 HTTP 请求。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/points")
@RequiredArgsConstructor
public class ProjectPointDataController {

    private final MeasurementQueryService service;

    @GetMapping("/latest")
    public Result<List<PointLatestVO>> latest(@PathVariable Long projectId) {
        return Result.ok(service.latestOfProject(projectId));
    }
}

