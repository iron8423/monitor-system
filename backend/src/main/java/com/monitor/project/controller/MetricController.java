package com.monitor.project.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.common.base.BaseCrudController;
import com.monitor.project.entity.Metric;
import com.monitor.project.mapper.MetricMapper;
import com.monitor.scope.service.DataScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测项 CRUD。数据范围见 {@link DataScopeService}。
 */
@RestController
@RequestMapping("/api/v1/metrics")
@RequiredArgsConstructor
public class MetricController extends BaseCrudController<Metric> {

    private final MetricMapper metricMapper;
    private final DataScopeService dataScope;

    @Override
    protected BaseMapper<Metric> mapper() {
        return metricMapper;
    }

    @Override
    protected LambdaQueryWrapper<Metric> scopeFilter() {
        return dataScope.metricFilter();
    }

    /** 测项 → 测点 → 对象 → 场景 → 项目，链子最长的一层。 */
    @Override
    protected boolean inScope(Metric entity) {
        return dataScope.canSeePoint(entity.getPointId());
    }
}
