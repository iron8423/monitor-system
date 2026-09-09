package com.monitor.project.controller;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.common.base.BaseCrudController;
import com.monitor.project.entity.Metric;
import com.monitor.project.mapper.MetricMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测项 CRUD。
 */
@RestController
@RequestMapping("/api/v1/metrics")
@RequiredArgsConstructor
public class MetricController extends BaseCrudController<Metric> {

    private final MetricMapper metricMapper;

    @Override
    protected BaseMapper<Metric> mapper() {
        return metricMapper;
    }
}
