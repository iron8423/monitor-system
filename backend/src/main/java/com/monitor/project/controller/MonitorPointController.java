package com.monitor.project.controller;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.common.base.BaseCrudController;
import com.monitor.project.entity.MonitorPoint;
import com.monitor.project.mapper.MonitorPointMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测点 CRUD。
 */
@RestController
@RequestMapping("/api/v1/points")
@RequiredArgsConstructor
public class MonitorPointController extends BaseCrudController<MonitorPoint> {

    private final MonitorPointMapper monitorPointMapper;

    @Override
    protected BaseMapper<MonitorPoint> mapper() {
        return monitorPointMapper;
    }
}
