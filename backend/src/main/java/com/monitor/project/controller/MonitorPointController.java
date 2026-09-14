package com.monitor.project.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.common.base.BaseCrudController;
import com.monitor.project.entity.MonitorPoint;
import com.monitor.project.mapper.MonitorPointMapper;
import com.monitor.scope.service.DataScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测点 CRUD。数据范围见 {@link DataScopeService}。
 *
 * <p>注意本类的 {@code GET /{id}} 与 {@code PointDataController} 的
 * {@code GET /{id}/latest} 是两条不同的路径，各自都要过范围：前者是档案，后者是数据。</p>
 */
@RestController
@RequestMapping("/api/v1/points")
@RequiredArgsConstructor
public class MonitorPointController extends BaseCrudController<MonitorPoint> {

    private final MonitorPointMapper monitorPointMapper;
    private final DataScopeService dataScope;

    @Override
    protected BaseMapper<MonitorPoint> mapper() {
        return monitorPointMapper;
    }

    @Override
    protected LambdaQueryWrapper<MonitorPoint> scopeFilter() {
        return dataScope.pointFilter();
    }

    /** 测点 → 对象 → 场景 → 项目。 */
    @Override
    protected boolean inScope(MonitorPoint entity) {
        return dataScope.canSeeObject(entity.getObjectId());
    }
}
