package com.monitor.project.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.asset.service.CalibrationService;
import com.monitor.audit.annotation.AuditAction;
import com.monitor.auth.security.SecurityUser;
import com.monitor.common.Result;
import com.monitor.common.base.BaseCrudController;
import com.monitor.project.entity.MonitorPoint;
import com.monitor.project.mapper.MonitorPointMapper;
import com.monitor.scope.service.DataScopeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
@SuppressWarnings("null")
public class MonitorPointController extends BaseCrudController<MonitorPoint> {

    private final MonitorPointMapper monitorPointMapper;
    private final DataScopeService dataScope;
    private final CalibrationService calibrationService;

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

    /**
     * 更新测点档案（清单第 09 条）。测点被移动（经纬度/高程变了）时，
     * **所有**观测它的标定随之失效——可能是多台雷达，测点一动它们的视线前提同时改变。
     *
     * <p>三个注解必须逐字重声明，理由见 {@code DeviceController#update}：
     * Java 方法注解不继承，而 Spring 的 {@code @annotation} 切点匹配最具体的方法。
     * 漏掉 {@code @Valid} 尤其隐蔽——{@code MonitorPoint.code} 上的 {@code @Size(max=64)}
     * 是全仓唯一一条实体级约束（B-13），漏了就真的没了。</p>
     */
    @Override
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @AuditAction(action = "更新")
    public Result<MonitorPoint> update(@PathVariable Long id, @Valid @RequestBody MonitorPoint entity) {
        return Result.ok(calibrationService.updatePoint(id, entity, currentUsername()).point());
    }

    /** 取法与 {@code AuditAspect.currentUser()} 一致；无安全上下文时返回 null。 */
    private static String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof SecurityUser su ? su.getUsername() : null;
    }
}
