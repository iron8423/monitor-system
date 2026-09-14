package com.monitor.organization.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.common.base.BaseCrudController;
import com.monitor.organization.entity.Organization;
import com.monitor.organization.mapper.OrganizationMapper;
import com.monitor.scope.service.DataScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户组织 CRUD。数据范围见 {@link DataScopeService}。
 *
 * <p>组织在项目<b>之上</b>，没有「组织的项目 id」这种字段，所以它是经<b>项目反查</b>的：
 * 可见组织 = 可见项目所属的那些组织。这样「西江水泥」对没有项目 B 成员关系的账号也是不可见的
 * ——否则组织列表会先把另一家客户的名字漏出去，而项目列表拦住了也没用。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations")
@RequiredArgsConstructor
public class OrganizationController extends BaseCrudController<Organization> {

    private final OrganizationMapper organizationMapper;
    private final DataScopeService dataScope;

    @Override
    protected BaseMapper<Organization> mapper() {
        return organizationMapper;
    }

    @Override
    protected LambdaQueryWrapper<Organization> scopeFilter() {
        return dataScope.organizationFilter();
    }

    @Override
    protected boolean inScope(Organization entity) {
        return dataScope.canSeeOrganization(entity.getId());
    }
}
