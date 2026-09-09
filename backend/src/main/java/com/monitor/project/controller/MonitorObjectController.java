package com.monitor.project.controller;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.common.base.BaseCrudController;
import com.monitor.project.entity.MonitorObject;
import com.monitor.project.mapper.MonitorObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 监测对象 CRUD。
 */
@RestController
@RequestMapping("/api/v1/objects")
@RequiredArgsConstructor
public class MonitorObjectController extends BaseCrudController<MonitorObject> {

    private final MonitorObjectMapper monitorObjectMapper;

    @Override
    protected BaseMapper<MonitorObject> mapper() {
        return monitorObjectMapper;
    }
}
