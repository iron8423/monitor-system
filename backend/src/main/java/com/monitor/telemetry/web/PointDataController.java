package com.monitor.telemetry.web;

import com.monitor.common.Result;
import com.monitor.common.exception.BizException;
import com.monitor.telemetry.dto.PointLatestVO;
import com.monitor.telemetry.dto.PointSeriesBatchVO;
import com.monitor.telemetry.dto.PointSeriesVO;
import com.monitor.telemetry.service.MeasurementQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

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

    /**
     * 批量 series（复查清单 P2-4）：一次取多个测点的同窗口曲线，给大屏回放用。
     *
     * <p><b>路径</b>：{@code GET /api/v1/points/series?pointIds=1,2,3&...}。放在这个字面量路径上
     * 与档案 CRUD 的 {@code GET /points/{id}} 不冲突——字面量优先于变量路径，且变量是数值类型；
     * 测点查询套件里有一条断言同时打这两个路径，防止哪天有人改坏了优先级。</p>
     *
     * <p>单次最多 200 个测点，超出 400（前端按 100 一批发）。不存在或不在数据范围内的测点在
     * 响应里回显（{@code skippedPointIds}），不静默吞掉——少了两条曲线和"本来只有两条"
     * 在图上一模一样。</p>
     */
    @GetMapping("/series")
    public Result<PointSeriesBatchVO> batchSeries(@RequestParam String pointIds,
                                                  @RequestParam(required = false) String metricCode,
                                                  @RequestParam(required = false) String from,
                                                  @RequestParam(required = false) String to,
                                                  @RequestParam(required = false) String granularity) {
        return Result.ok(service.batchSeries(parsePointIds(pointIds), metricCode, from, to, granularity));
    }

    /**
     * {@code "1,2,3"} → {@code [1,2,3]}。
     *
     * <p>非数字项**直接 400 并指出是哪一个**，不跳过：静默丢一个 id，调用方看到的是
     * "少了一条曲线"，与"这个点没有数据"没法区分。</p>
     */
    static List<Long> parsePointIds(String raw) {
        List<Long> ids = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.valueOf(trimmed));
            } catch (NumberFormatException e) {
                throw new BizException("pointIds 里出现非数字项: " + trimmed);
            }
        }
        if (ids.isEmpty()) {
            throw new BizException("pointIds 不能为空");
        }
        return ids;
    }
}
