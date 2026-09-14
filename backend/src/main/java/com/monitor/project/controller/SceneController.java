package com.monitor.project.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.common.base.BaseCrudController;
import com.monitor.project.entity.Scene;
import com.monitor.project.mapper.SceneMapper;
import com.monitor.scope.service.DataScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 场景 CRUD。数据范围见 {@link DataScopeService}。
 */
@RestController
@RequestMapping("/api/v1/scenes")
@RequiredArgsConstructor
public class SceneController extends BaseCrudController<Scene> {

    private final SceneMapper sceneMapper;
    private final DataScopeService dataScope;

    @Override
    protected BaseMapper<Scene> mapper() {
        return sceneMapper;
    }

    @Override
    protected LambdaQueryWrapper<Scene> scopeFilter() {
        return dataScope.sceneFilter();
    }

    /** 场景直接挂项目，沿链上溯一层。 */
    @Override
    protected boolean inScope(Scene entity) {
        return dataScope.canSeeProject(entity.getProjectId());
    }
}
