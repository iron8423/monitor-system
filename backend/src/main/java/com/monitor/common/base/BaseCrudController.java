package com.monitor.common.base;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.monitor.audit.annotation.AuditAction;
import com.monitor.common.PageResult;
import com.monitor.common.Result;
import com.monitor.common.exception.BizException;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 通用 CRUD 控制器基类：读开放给所有登录用户，写仅 ADMIN。
 * 子类只需注入 Mapper 并实现 {@link #mapper()}。
 */
public abstract class BaseCrudController<T extends BaseEntity> {

    protected abstract BaseMapper<T> mapper();

    @GetMapping
    public Result<List<T>> list() {
        return Result.ok(mapper().selectList(null));
    }

    @GetMapping("/page")
    public Result<PageResult<T>> page(@RequestParam(defaultValue = "1") long pageNum,
                                      @RequestParam(defaultValue = "10") long pageSize) {
        Page<T> page = mapper().selectPage(new Page<>(pageNum, pageSize), null);
        return Result.ok(PageResult.of(page));
    }

    @GetMapping("/{id}")
    public Result<T> get(@PathVariable Long id) {
        T entity = mapper().selectById(id);
        if (entity == null) {
            throw new BizException(404, "记录不存在: " + id);
        }
        return Result.ok(entity);
    }

    /**
     * {@code @Valid} 在这里对**所有**子类生效，但只有声明了约束注解的实体才真的会被校验——
     * 目前只有 {@link com.monitor.project.entity.MonitorPoint#getCode()} 一个（B-13）。
     * 其余实体没有任何约束注解，`@Valid` 对它们是空操作，所以加在这里是安全的；
     * 校验失败由 {@code GlobalExceptionHandler} 统一转成 400 + 字段文案。
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @AuditAction(action = "创建")
    public Result<T> create(@Valid @RequestBody T entity) {
        mapper().insert(entity);
        return Result.ok(entity);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @AuditAction(action = "更新")
    public Result<T> update(@PathVariable Long id, @Valid @RequestBody T entity) {
        entity.setId(id);
        mapper().updateById(entity);
        return Result.ok(mapper().selectById(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @AuditAction(action = "删除")
    public Result<Void> delete(@PathVariable Long id) {
        mapper().deleteById(id);
        return Result.ok();
    }
}
