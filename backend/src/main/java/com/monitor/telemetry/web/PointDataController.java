package com.monitor.telemetry.web;

import com.monitor.common.Result;
import com.monitor.telemetry.dto.PointLatestVO;
import com.monitor.telemetry.dto.PointSeriesVO;
import com.monitor.telemetry.service.MeasurementQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * M1：测点数据查询端点（《B侧接口契约_M0》§3）。档案 CRUD 仍在 A 的 MonitorPointController。
 */
@RestController
@RequestMapping("/api/v1/points")
public class PointDataController {

    private final MeasurementQueryService service;

    public PointDataController(MeasurementQueryService service) {
        this.service = service;
    }

    @GetMapping("/{pointId}/latest")
    public Result<PointLatestVO> latest(@PathVariable Long pointId) {
        return Result.ok(service.latest(pointId));
    }

    @GetMapping("/{pointId}/series")
    public Result<PointSeriesVO> series(@PathVariable Long pointId,
                                        @RequestParam(required = false) String metricCode,
                                        @RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to,
                                        @RequestParam(required = false) String granularity) {
        return Result.ok(service.series(pointId, metricCode, from, to, granularity));
    }
}
