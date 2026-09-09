package com.monitor.project.controller;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.common.base.BaseCrudController;
import com.monitor.project.entity.Project;
import com.monitor.project.mapper.ProjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目档案 CRUD。
 */
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController extends BaseCrudController<Project> {

    private final ProjectMapper projectMapper;

    @Override
    protected BaseMapper<Project> mapper() {
        return projectMapper;
    }
}
