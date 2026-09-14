package com.monitor.project.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.common.base.BaseCrudController;
import com.monitor.project.entity.Project;
import com.monitor.project.mapper.ProjectMapper;
import com.monitor.scope.service.DataScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目档案 CRUD。数据范围见 {@link DataScopeService}。
 */
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController extends BaseCrudController<Project> {

    private final ProjectMapper projectMapper;
    private final DataScopeService dataScope;

    @Override
    protected BaseMapper<Project> mapper() {
        return projectMapper;
    }

    /** 项目自己就是范围链的根。 */
    @Override
    protected LambdaQueryWrapper<Project> scopeFilter() {
        return dataScope.projectFilter();
    }

    @Override
    protected boolean inScope(Project entity) {
        return dataScope.canSeeProject(entity.getId());
    }
}
