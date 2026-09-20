package com.monitor.telemetry.web;

import com.monitor.common.Result;
import com.monitor.telemetry.dto.ProjectSummaryVO;
import com.monitor.telemetry.service.ProjectSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目概览（《B侧接口契约_M0》§3）。
 *
 * <p>路径挂在 {@code /projects} 下但与 {@code ProjectController} 的 {@code GET /{id}} 不冲突：
 * 本接口是两段路径 {@code /{projectId}/summary}，按段数即区分。</p>
 */
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectSummaryController {

    private final ProjectSummaryService summaryService;

    @GetMapping("/{projectId}/summary")
    public Result<ProjectSummaryVO> summary(@PathVariable Long projectId) {
        return Result.ok(summaryService.summary(projectId));
    }
}
