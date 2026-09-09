package com.monitor.organization.controller;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.common.base.BaseCrudController;
import com.monitor.organization.entity.Organization;
import com.monitor.organization.mapper.OrganizationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户组织 CRUD。
 */
@RestController
@RequestMapping("/api/v1/organizations")
@RequiredArgsConstructor
public class OrganizationController extends BaseCrudController<Organization> {

    private final OrganizationMapper organizationMapper;

    @Override
    protected BaseMapper<Organization> mapper() {
        return organizationMapper;
    }
}
