package com.monitor.project.controller;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.common.base.BaseCrudController;
import com.monitor.project.entity.Scene;
import com.monitor.project.mapper.SceneMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 场景 CRUD。
 */
@RestController
@RequestMapping("/api/v1/scenes")
@RequiredArgsConstructor
public class SceneController extends BaseCrudController<Scene> {

    private final SceneMapper sceneMapper;

    @Override
    protected BaseMapper<Scene> mapper() {
        return sceneMapper;
    }
}
