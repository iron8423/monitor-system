package com.monitor.common.base;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.monitor.audit.annotation.AuditAction;
import com.monitor.common.PageResult;
import com.monitor.common.Result;
import com.monitor.common.exception.BizException;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.ArrayList;

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
@Slf4j
public abstract class BaseCrudController<T extends BaseEntity>
        implements com.monitor.audit.spi.AuditSnapshotSource {

    /**
     * 列表端点的默认上限（P1-3）。
     *
     * <p>取值理由：本仓记录的生产基线是「单项目 1000 测点、每点 2 测项」，于是
     * {@code /points} 约 1000 行、{@code /metrics} 约 2000 行。默认 2000 意味着
     * **基线规模下不会有任何端点被截断**，而它挡的是"数据长到十倍之后列表把整个
     * 单点响应撑到几 MB"这件事。</p>
     */
    protected static final long DEFAULT_LIST_LIMIT = 2000;

    /** 显式 {@code ?limit=} 的上限：再大就不该用列表端点，而该走分页或导出。 */
    protected static final long MAX_LIST_LIMIT = 10000;

    /** 被截断时回给调用方的响应头：截断必须**说出来**，静默少几行比报错更难查。 */
    public static final String HEADER_LIMIT = "X-Result-Limit";
    public static final String HEADER_TRUNCATED = "X-Result-Truncated";

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

    /**
     * 审计用的「按主键取当前值」（P1-8，见 {@code AuditSnapshotSource}）。
     * 放在基类里，于是所有档案类端点（项目/场景/对象/测点/设备/测项/组织）自动带上前后值。
     */
    @Override
    public Object auditSnapshot(Long id) {
        return id == null ? null : mapper().selectById(id);
    }

    /**
     * 列表与分页的排序钩子。默认**不加排序**（基类拿不到子类实体的主键 lambda——
     * {@code LambdaQueryWrapper} 只接受 {@code SFunction}，不接受列名字符串）。
     *
     * <p><b>顺序参与判断的资源必须覆盖它。</b>反例（2026-09-17 实测）：{@code GET /projects}
     * 没有 ORDER BY 时，返回顺序由执行计划决定——同一份数据把「西江水泥采空区」
     * （{@code assetType=NONE}，没配数字孪生场景）排在了「清远山地边坡」前面，而前端
     * {@code monitor.js} 拿 {@code projects[0]} 当默认项目，于是 **3D 大屏默认打开一个没场景的项目**，
     * 屏幕上只剩「场景未配置 / 离线底色」，看起来就是「大屏打不开」。</p>
     *
     * <p>这与「取最新一行必须带 id 兜底」是同一类缺陷：只要顺序被当成结论用，就必须写死。
     * 分页同理——不排序的分页会出现「第 2 页翻出第 1 页的行、有的行永远看不到」。</p>
     */
    protected LambdaQueryWrapper<T> ordered(LambdaQueryWrapper<T> wrapper) {
        return wrapper;
    }

    /**
     * 统一构造列表/分页的查询条件。
     *
     * <p>存在的理由是把 {@code scopeFilter()} 的 <b>null 语义</b>归一掉：它的约定是
     * 「null = 不受限」（ADMIN 视角就是 null，见 {@link #scopeFilter()} 的 javadoc），
     * 而 {@code ordered()} 的实现要往 wrapper 上挂排序——直接透传 null 会 NPE。
     * 归一成空 wrapper 与 null 在语义上等价（都表示「没有额外条件」），
     * 于是每个子类的 {@code ordered()} 都可以假定参数非空。</p>
     */
    private LambdaQueryWrapper<T> baseQuery() {
        LambdaQueryWrapper<T> wrapper = scopeFilter();
        return ordered(wrapper == null ? new LambdaQueryWrapper<>() : wrapper);
    }

    /**
     * 列表：**默认带上限**，并在被截断时通过响应头明说（P1-3）。
     *
     * <p>为什么不是"超限就 400"：列表端点是只读的，直接报错会让整个页面打不开，
     * 而用户真正需要的往往是"先看到前 N 行"。为什么不是"悄悄截断"：少几行与数据真的
     * 只有几行，在响应体里长得一模一样——截断必须由 {@code X-Result-Truncated} 说出来，
     * 前端据此提示，运维据此改用 {@code /page} 或加 {@code limit}。</p>
     *
     * <p>多查一行（{@code limit + 1}）是判"到底有没有被截"的唯一可靠办法：
     * 行数**恰好等于**上限时不叫截断，而靠等号去猜会把这种合法情况误报。</p>
     */
    @GetMapping
    public Result<List<T>> list(@RequestParam(required = false) Long limit,
                                HttpServletResponse response) {
        return Result.ok(listCapped(limit, response));
    }

    /**
     * 按上限取列表（受数据范围限制）。子类覆盖了 {@code list()} 的（设备端点要推导状态）
     * 也走这里，保证"上限与截断标注"只有一份实现。
     */
    protected List<T> listCapped(Long limit, HttpServletResponse response) {
        long effective = effectiveLimit(limit);
        List<T> rows = mapper().selectList(baseQuery().last("LIMIT " + (effective + 1)));
        boolean truncated = rows.size() > effective;
        if (truncated) {
            rows = new ArrayList<>(rows.subList(0, (int) effective));
            log.warn("列表被上限截断：{} 请求 limit={}（可用 /page 分页或调大 limit，最大 {}）",
                    getClass().getSimpleName(), effective, MAX_LIST_LIMIT);
        }
        if (response != null) {
            response.setHeader(HEADER_LIMIT, String.valueOf(effective));
            response.setHeader(HEADER_TRUNCATED, String.valueOf(truncated));
        }
        return rows;
    }

    /** 入参归一：缺省用默认上限，超出区间一律夹到边界（并把生效值回给调用方）。 */
    protected static long effectiveLimit(Long limit) {
        if (limit == null) {
            return DEFAULT_LIST_LIMIT;
        }
        return Math.min(Math.max(limit, 1L), MAX_LIST_LIMIT);
    }

    @GetMapping("/page")
    public Result<PageResult<T>> page(@RequestParam(defaultValue = "1") long pageNum,
                                      @RequestParam(defaultValue = "10") long pageSize) {
        Page<T> page = mapper().selectPage(new Page<>(pageNum, pageSize), baseQuery());
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
