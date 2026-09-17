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

    /**
     * 项目列表按 id 升序 —— **默认打开哪个项目**这件事依赖它。
     *
     * <p>前端 {@code stores/monitor.js} 用 {@code projects[0]} 当默认项目（大屏、工作台都读它）。
     * 不加排序时顺序由执行计划决定：2026-09-17 实测同一份数据把「西江水泥采空区」
     * （没配数字孪生场景）排在了「清远山地边坡」前面，于是 **3D 大屏默认落在没场景的项目上**，
     * 屏幕上只剩「场景未配置 / 离线底色」——用户看到的就是「大屏打不开」。
     * 排序写死之后，默认项目才是稳定的（清远山地边坡 = 种子里 id=1 的那个）。</p>
     */
    @Override
    protected LambdaQueryWrapper<Project> ordered(LambdaQueryWrapper<Project> wrapper) {
        return wrapper.orderByAsc(Project::getId);
    }
}
