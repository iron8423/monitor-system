package com.monitor.project.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.common.base.BaseCrudController;
import com.monitor.project.entity.MonitorObject;
import com.monitor.project.mapper.MonitorObjectMapper;
import com.monitor.scope.service.DataScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 监测对象 CRUD。数据范围见 {@link DataScopeService}。
 */
@RestController
@RequestMapping("/api/v1/objects")
@RequiredArgsConstructor
public class MonitorObjectController extends BaseCrudController<MonitorObject> {

    private final MonitorObjectMapper monitorObjectMapper;
    private final DataScopeService dataScope;

    @Override
    protected BaseMapper<MonitorObject> mapper() {
        return monitorObjectMapper;
    }

    @Override
    protected LambdaQueryWrapper<MonitorObject> scopeFilter() {
        return dataScope.objectFilter();
    }

    /** 对象 → 场景 → 项目。用实体上现成的 sceneId 判，不必再查一次对象。 */
    @Override
    protected boolean inScope(MonitorObject entity) {
        return dataScope.canSeeScene(entity.getSceneId());
    }
}
