package com.monitor.common.base;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
 * 子类只需注入 Mapper 并实现 {@link #mapper()}，以及数据范围的两个钩子。
 *
 * <p><b>读一律过数据范围</b>（验收第 7 条前半）：{@code list} / {@code page} / {@code get} 三条
 * 读取路径都会套 {@link #scopeFilter()} 与 {@link #inScope}。两个钩子刻意声明为
 * <b>abstract</b>——漏了它的后果不是报错，而是这个档案对所有人可见，
 * 所以宁可让新控制器编译不过，也不要它默认「不限制」。{@code OrganizationController}
 * 看着与项目无关，其实也要实现（组织经项目反查），这正是不给默认实现的原因。</p>
 *
 * <p>写（{@code create}/{@code update}/{@code delete}）不套范围：它们的
 * {@code @PreAuthorize("hasRole('ADMIN')")} 已经把调用者限定成 ADMIN，
 * 而 ADMIN 在数据范围上本就不受限，再加一层是空条件。</p>
 */
public abstract class BaseCrudController<T extends BaseEntity> {

    protected abstract BaseMapper<T> mapper();

    /**
     * 列表/分页的数据范围条件；{@code null} 表示不受限（ADMIN）。
     * 实现一律取自 {@code DataScopeService} 的对应 {@code xxxFilter()}。
     */
    protected abstract LambdaQueryWrapper<T> scopeFilter();

    /**
     * 单条记录是否在当前用户的数据范围内（{@code GET /{id}} 用）。
     *
     * <p>必须与 {@link #scopeFilter()} <b>同口径</b>：一个走 SQL、一个走内存，
     * 两处一旦不一致就会出现「列表里看不到、直达却能打开」这类只在单条路径上漏的洞，
     * 而列表断言是绿的。</p>
     */
    protected abstract boolean inScope(T entity);

    @GetMapping
    public Result<List<T>> list() {
        return Result.ok(mapper().selectList(scopeFilter()));
    }

    @GetMapping("/page")
    public Result<PageResult<T>> page(@RequestParam(defaultValue = "1") long pageNum,
                                      @RequestParam(defaultValue = "10") long pageSize) {
        Page<T> page = mapper().selectPage(new Page<>(pageNum, pageSize), scopeFilter());
        return Result.ok(PageResult.of(page));
    }

    @GetMapping("/{id}")
    public Result<T> get(@PathVariable Long id) {
        T entity = mapper().selectById(id);
        if (entity == null) {
            throw new BizException(404, "记录不存在: " + id);
        }
        if (!inScope(entity)) {
            // 403 而不是 404：记录确实存在，只是不在你的数据范围内（口径见 DataScopeService）
            throw new BizException(403, "无权访问该记录: " + id);
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
