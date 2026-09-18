package com.monitor.scope.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.monitor.alarm.entity.Alarm;
import com.monitor.alarm.entity.AlarmRule;
import com.monitor.asset.entity.Device;
import com.monitor.asset.entity.DevicePoint;
import com.monitor.asset.entity.MaintenanceRecord;
import com.monitor.asset.mapper.DevicePointMapper;
import com.monitor.auth.security.SecurityUser;
import com.monitor.common.constant.Role;
import com.monitor.common.exception.BizException;
import com.monitor.common.sse.SubscriberScope;
import com.monitor.organization.entity.Organization;
import com.monitor.project.entity.Metric;
import com.monitor.project.entity.MonitorObject;
import com.monitor.project.entity.MonitorPoint;
import com.monitor.project.entity.Project;
import com.monitor.project.entity.Scene;
import com.monitor.project.mapper.MonitorObjectMapper;
import com.monitor.project.mapper.MonitorPointMapper;
import com.monitor.project.mapper.ProjectMapper;
import com.monitor.project.mapper.SceneMapper;
import com.monitor.scope.entity.ProjectMember;
import com.monitor.scope.mapper.ProjectMemberMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 项目数据范围隔离（验收第 7 条前半）。**全部范围规则都只在本类里**——
 * 想知道「某个端点有没有套隔离」，读这一个文件即可，不必翻遍控制器。
 *
 * <h3>可见范围的定义</h3>
 * 可见项目 = 你在 {@code project_member} 里的那些；其余（场景/对象/测点/测项/设备/警情/影像）
 * 一律**沿归属链反推**：测点 → 对象 → 场景 → 项目，设备经 {@code device_point} → 测点。
 * ADMIN 不受限（平台管理员，需求里就是全量视角）。
 *
 * <h3>三处必须写对的地方</h3>
 * <ol>
 *   <li><b>空集合必须短路成「查不到」。</b>MyBatis-Plus 的 {@code in(空集合)} 会把条件
 *       <em>整条丢掉</em>，于是「你没有可见项目」会退化成「所有项目都可见」——这是隔离最不能出的
 *       错，而且是 fail-open 的静默错误。本类统一用 {@link #inIds} 兜住。</li>
 *   <li><b>派生集合要逐层判空。</b>「可见测点为空 → 去查这些测点的设备」如果不判空，
 *       那次查询的条件会被丢掉，返回的是<b>全部</b>设备-测点绑定，再反推出全部设备。
 *       每一层都先 {@code isEmpty()} 返回，见各 {@code visibleXxxIdsOrNull}。</li>
 *   <li><b>没有认证上下文时拒绝，不是放行。</b>本类的调用点全部在 HTTP 控制器链路上
 *       （已逐个核对过：这些 service 没有任何内部调用者），所以「取不到当前用户」意味着
 *       调用姿势错了。此时放行会静默漏数据，拒绝只会让那一个请求 403——选后者。</li>
 * </ol>
 *
 * <p>未受限时各 {@code xxxFilter()} 一律返回 {@code null}（= 不加条件），
 * 而不是去查一遍全量 id 再 {@code in} 进来：前者是「不限制」，后者是「限制成一个刚好等于全集的集合」，
 * 数据一多就是白扫。</p>
 */
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class DataScopeService implements SubscriberScope {

    private final ProjectMemberMapper memberMapper;
    private final ProjectMapper projectMapper;
    private final SceneMapper sceneMapper;
    private final MonitorObjectMapper objectMapper;
    private final MonitorPointMapper pointMapper;
    private final DevicePointMapper devicePointMapper;

    // ==================== 当前用户 ====================

    /**
     * 当前登录用户；无认证上下文（未认证或非 HTTP 调用）返回 {@code null}。
     * 判据是 principal 的类型而不是「认证对象非空」——匿名认证的 principal 是字符串 {@code "anonymousUser"}。
     */
    private SecurityUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof SecurityUser su ? su : null;
    }

    /** ADMIN 不受数据范围限制（平台管理员，需求里就是全量视角）。 */
    public boolean unrestricted() {
        SecurityUser u = currentUser();
        return u != null && Role.ADMIN.name().equals(u.getRole());
    }

    // ==================== 可见集合（仅受限时有意义） ====================

    /**
     * 可见项目 id；**未受限（ADMIN）返回 {@code null}**，表示「不受限」而不是「没有」。
     * 只在受限路径上被调用，调用方一律先判 {@code null} 再决定要不要加条件。
     */
    private Set<Long> visibleProjectIdsOrNull() {
        SecurityUser u = currentUser();
        return visibleProjectIdsOf(u == null ? null : u.getId(), u == null ? null : u.getRole());
    }

    /**
     * 指定用户的可见项目 id（{@code null} = 不受限）。{@link #visibleProjectIdsOrNull()} 委托到这里。
     *
     * <p><b>为什么要有这个「带用户参数」的版本</b>：SSE 广播发生在<b>没有 HTTP 请求</b>的线程上
     * （心跳线程 / 事务提交后回调），那里 {@code SecurityContextHolder} 是空的，
     * 「当前用户」这个概念不存在——只有「这条连接当初是谁建的」。
     * 推送侧因此必须能按<b>显式传入的用户</b>算范围，而不是去读上下文。
     * 两条路径共用这一个方法，才不会出现「查询挡住了、推送漏出去」。</p>
     */
    @Override
    public Set<Long> visibleProjectIdsOf(Long userId, String role) {
        if (Role.ADMIN.name().equals(role)) {
            return null;
        }
        if (userId == null) {
            return Set.of();
        }
        return memberMapper.selectList(new LambdaQueryWrapper<ProjectMember>()
                        .eq(ProjectMember::getUserId, userId))
                .stream().map(ProjectMember::getProjectId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private Set<Long> visibleSceneIdsOrNull() {
        Set<Long> projects = visibleProjectIdsOrNull();
        if (projects == null) {
            return null;
        }
        // 必须显式短路：空集合交给 in() 会被 MP 丢掉条件，查出全部场景
        if (projects.isEmpty()) {
            return Set.of();
        }
        return sceneMapper.selectList(new LambdaQueryWrapper<Scene>()
                        .in(Scene::getProjectId, projects))
                .stream().map(Scene::getId).collect(Collectors.toSet());
    }

    private Set<Long> visibleObjectIdsOrNull() {
        Set<Long> scenes = visibleSceneIdsOrNull();
        if (scenes == null) {
            return null;
        }
        if (scenes.isEmpty()) {
            return Set.of();
        }
        return objectMapper.selectList(new LambdaQueryWrapper<MonitorObject>()
                        .in(MonitorObject::getSceneId, scenes))
                .stream().map(MonitorObject::getId).collect(Collectors.toSet());
    }

    private Set<Long> visiblePointIdsOrNull() {
        Set<Long> objects = visibleObjectIdsOrNull();
        if (objects == null) {
            return null;
        }
        if (objects.isEmpty()) {
            return Set.of();
        }
        return pointMapper.selectList(new LambdaQueryWrapper<MonitorPoint>()
                        .in(MonitorPoint::getObjectId, objects))
                .stream().map(MonitorPoint::getId).collect(Collectors.toSet());
    }

    /** 项目下的设备：经 {@code device_point} 反查测点。传入空集合必须返回空——否则会反推出全部设备。 */
    private Set<Long> deviceIdsOfPoints(Collection<Long> pointIds) {
        if (pointIds.isEmpty()) {
            return Set.of();
        }
        return devicePointMapper.selectList(new LambdaQueryWrapper<DevicePoint>()
                        .in(DevicePoint::getPointId, pointIds))
                .stream().map(DevicePoint::getDeviceId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private Set<Long> visibleDeviceIdsOrNull() {
        Set<Long> points = visiblePointIdsOrNull();
        return points == null ? null : deviceIdsOfPoints(points);
    }

    private Set<Long> visibleOrganizationIdsOrNull() {
        Set<Long> projects = visibleProjectIdsOrNull();
        if (projects == null) {
            return null;
        }
        if (projects.isEmpty()) {
            return Set.of();
        }
        return projectMapper.selectByIds(projects).stream()
                .map(Project::getOrganizationId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    // ==================== 反向归属（事件 -> 项目，推送侧用） ====================

    /**
     * 测点归属的项目 id。无归属（测点不存在，或对象/场景/项目链断掉）返回<b>空集</b>——
     * 空集在各调用点的含义是「只有管理员看得到」，与 CRUD 侧同一条规则。
     *
     * <p>返回集合而不是单个 id：调用方（SSE 过滤）需要和设备的「多归属」用同一种形状，
     * 免得两处各判一次空。</p>
     */
    public Set<Long> projectIdsOfPoint(Long pointId) {
        MonitorPoint p = pointId == null ? null : pointMapper.selectById(pointId);
        MonitorObject o = p == null || p.getObjectId() == null ? null : objectMapper.selectById(p.getObjectId());
        Scene s = o == null || o.getSceneId() == null ? null : sceneMapper.selectById(o.getSceneId());
        return s == null || s.getProjectId() == null ? Set.of() : Set.of(s.getProjectId());
    }

    /**
     * 设备归属的项目 id：经 {@code device_point} 反查它绑过的测点，再沿测点链条上溯。
     *
     * <p><b>可能多于一个</b>——{@code device_point} 上没有「一台设备只属于一个项目」的约束，
     * 一台设备可以绑两个项目的测点。这时它的设备告警对这两个项目都可见，
     * 与 {@link #canSeeDevice} 的「任一命中」是同一条规则。</p>
     *
     * <p>没绑任何测点的新设备返回空集（无归属 → 只对 ADMIN 可见），这与 CRUD 侧一致：
     * 否则「刚建好还没绑点」的设备，它的状态对所有人都可见。</p>
     */
    public Set<Long> projectIdsOfDevice(Long deviceId) {
        if (deviceId == null) {
            return Set.of();
        }
        List<Long> pointIds = devicePointMapper.selectList(new LambdaQueryWrapper<DevicePoint>()
                        .eq(DevicePoint::getDeviceId, deviceId))
                .stream().map(DevicePoint::getPointId).filter(Objects::nonNull).toList();
        // 逐层判空：空集合交给 in() 会被 MP 丢掉条件，那就成了「上溯出全部项目」
        if (pointIds.isEmpty()) {
            return Set.of();
        }
        List<Long> objectIds = pointMapper.selectByIds(pointIds).stream()
                .map(MonitorPoint::getObjectId).filter(Objects::nonNull).distinct().toList();
        if (objectIds.isEmpty()) {
            return Set.of();
        }
        List<Long> sceneIds = objectMapper.selectByIds(objectIds).stream()
                .map(MonitorObject::getSceneId).filter(Objects::nonNull).distinct().toList();
        if (sceneIds.isEmpty()) {
            return Set.of();
        }
        return sceneMapper.selectByIds(sceneIds).stream()
                .map(Scene::getProjectId).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    /**
     * 警情归属的项目 id——{@link #canSeeAlarm} 的镜像，两侧取并集。
     *
     * <p>与判定侧一样写「取并集」而不是「按 alarmType 分支」：分支写法在新增第三种告警类型时
     * 会默认落到 else，而那一支判的是 NULL 字段。并集对未知类型的行为是空集 → 只发 ADMIN，
     * fail-closed。两处若各写一套，就会出现「详情页挡得住、推送发得出去」。</p>
     */
    public Set<Long> projectIdsOfAlarm(Alarm alarm) {
        if (alarm == null) {
            return Set.of();
        }
        Set<Long> ids = new HashSet<>(projectIdsOfPoint(alarm.getPointId()));
        ids.addAll(projectIdsOfDevice(alarm.getDeviceId()));
        return ids;
    }

    // ==================== 单条判定 ====================
    /** {@code id} 为 {@code null} 表示该记录**无归属**（如刚建好、还没挂测点的设备）——只对 ADMIN 可见。 */
    public boolean canSeeProject(Long projectId) {
        if (unrestricted()) {
            return true;
        }
        return projectId != null && visibleProjectIdsOrNull().contains(projectId);
    }

    public boolean canSeeScene(Long sceneId) {
        if (unrestricted()) {
            return true;
        }
        return sceneId != null && visibleSceneIdsOrNull().contains(sceneId);
    }

    public boolean canSeeObject(Long objectId) {
        if (unrestricted()) {
            return true;
        }
        return objectId != null && visibleObjectIdsOrNull().contains(objectId);
    }

    public boolean canSeePoint(Long pointId) {
        if (unrestricted()) {
            return true;
        }
        return pointId != null && visiblePointIdsOrNull().contains(pointId);
    }

    public boolean canSeeDevice(Long deviceId) {
        if (unrestricted()) {
            return true;
        }
        return deviceId != null && visibleDeviceIdsOrNull().contains(deviceId);
    }

    public boolean canSeeOrganization(Long organizationId) {
        if (unrestricted()) {
            return true;
        }
        return organizationId != null && visibleOrganizationIdsOrNull().contains(organizationId);
    }

    /**
     * 警情可见性：两侧任一命中即可（{@code POINT} 类只看 {@code point_id}、
     * {@code DEVICE} 类只看 {@code device_id}，另一侧为 NULL）。
     *
     * <p>写成「任一命中」而不是「按 alarmType 分支」，是因为分支写法在新增第三种
     * 告警类型时会**默认落到 else 那一支**——而那一支判的是一个 NULL 字段，
     * 结果是新类型警情谁都看不见（或更糟：按 else 走成「全可见」）。
     * 「任一命中」对未知类型的行为是「两侧都判不出来 → 不可见」，fail-closed。</p>
     *
     * <p>一台设备同时绑了 A、B 两个项目的测点时，它的设备告警对两边都可见——
     * 这是「设备是共享资产」的直接后果，不是漏洞。要让设备告警只归一边，
     * 得先定「一台设备能不能跨项目」这条业务规则。</p>
     */
    public boolean canSeeAlarm(Alarm alarm) {
        if (unrestricted()) {
            return true;
        }
        return alarm != null
                && (canSeePoint(alarm.getPointId()) || canSeeDevice(alarm.getDeviceId()));
    }

    public void assertAlarmVisible(Alarm alarm) {
        if (unrestricted()) {
            return;
        }
        if (!canSeeAlarm(alarm)) {
            Long id = alarm == null ? null : alarm.getId();
            throw new BizException(403, "无权访问该警情: " + id);
        }
    }

    // ==================== 断言（不可见 -> 403） ====================

    /**
     * 不可见抛 <b>403</b> 而不是 404。404 更「安全」（不泄露存在性），但演示时要的是
     * 「看得见边界」——403 一眼就知道是隔离在起作用，404 会被误当成「数据没了」。
     * 这是用户可感知的取舍，写在契约里。
     */
    public void assertProjectVisible(Long projectId) {
        if (unrestricted()) {
            return;
        }
        if (projectId == null) {
            throw new BizException(403, "该记录尚未归属任何项目，仅管理员可见");
        }
        if (!canSeeProject(projectId)) {
            throw new BizException(403, "无权访问该项目的数据: " + projectId);
        }
    }

    public void assertPointVisible(Long pointId) {
        if (unrestricted()) {
            return;
        }
        if (pointId == null) {
            throw new BizException(403, "该记录尚未归属任何项目，仅管理员可见");
        }
        if (!canSeePoint(pointId)) {
            throw new BizException(403, "无权访问该测点的数据: " + pointId);
        }
    }

    public void assertDeviceVisible(Long deviceId) {
        if (unrestricted()) {
            return;
        }
        if (!canSeeDevice(deviceId)) {
            throw new BizException(403, "无权访问该设备的数据: " + deviceId);
        }
    }

    // ==================== 列表条件（未受限返回 null） ====================

    public LambdaQueryWrapper<Project> projectFilter() {
        Set<Long> ids = visibleProjectIdsOrNull();
        return ids == null ? null : inIds(Project::getId, ids);
    }

    public LambdaQueryWrapper<Scene> sceneFilter() {
        Set<Long> ids = visibleProjectIdsOrNull();
        return ids == null ? null : inIds(Scene::getProjectId, ids);
    }

    public LambdaQueryWrapper<MonitorObject> objectFilter() {
        Set<Long> ids = visibleObjectIdsOrNull();
        return ids == null ? null : inIds(MonitorObject::getId, ids);
    }

    public LambdaQueryWrapper<MonitorPoint> pointFilter() {
        Set<Long> ids = visiblePointIdsOrNull();
        return ids == null ? null : inIds(MonitorPoint::getId, ids);
    }

    public LambdaQueryWrapper<Metric> metricFilter() {
        Set<Long> ids = visiblePointIdsOrNull();
        return ids == null ? null : inIds(Metric::getPointId, ids);
    }

    /** 无归属的设备（未绑定任何测点）只对 ADMIN 可见——否则建完就「消失」，没法再挂测点。 */
    public LambdaQueryWrapper<Device> deviceFilter() {
        Set<Long> ids = visibleDeviceIdsOrNull();
        return ids == null ? null : inIds(Device::getId, ids);
    }

    public LambdaQueryWrapper<Organization> organizationFilter() {
        Set<Long> ids = visibleOrganizationIdsOrNull();
        return ids == null ? null : inIds(Organization::getId, ids);
    }

    public LambdaQueryWrapper<MaintenanceRecord> maintenanceRecordFilter() {
        Set<Long> ids = visibleDeviceIdsOrNull();
        return ids == null ? null : inIds(MaintenanceRecord::getDeviceId, ids);
    }

    /**
     * 把警情范围挂到调用方已建好的条件上（不能返回一个新条件：调用方还有 level/status/时间等筛选要合并）。
     *
     * <p>两条来源都要滤：{@code POINT} 类警情只写 {@code point_id}、{@code DEVICE} 类只写
     * {@code device_id}（另一侧为 NULL）。只滤一侧的话，设备告警那一侧永远漏——
     * 这与 B-14（项目概览漏算设备告警）是同一个坑，只是方向相反：
     * 那次是「少算」，这次是「多给」。</p>
     *
     * <p>两侧都空时**不能**挂 {@code or()} 分支：{@code in(空集合)} 会丢条件，而外层 and 里
     * 兄弟条件还在，于是退化成「所有警情都可见」。这种时候直接 {@code 1 = 0}。</p>
     */
    public void applyAlarmScope(LambdaQueryWrapper<Alarm> wrapper) {
        Set<Long> pointIds = visiblePointIdsOrNull();
        if (pointIds == null) {
            return;   // ADMIN 不受限
        }
        if (pointIds.isEmpty()) {
            wrapper.apply("1 = 0");
            return;
        }
        Set<Long> deviceIds = deviceIdsOfPoints(pointIds);
        wrapper.and(w -> {
            w.in(Alarm::getPointId, pointIds);
            if (!deviceIds.isEmpty()) {
                w.or().in(Alarm::getDeviceId, deviceIds);
            }
        });
    }

    /**
     * 告警规则的可见范围（V22 起按作用域分三组取并集）：
     * <ol>
     *   <li><b>全局规则</b>（{@code point_id} 与 {@code project_id} 都为空）对所有登录用户可见；</li>
     *   <li><b>项目规则</b>（{@code point_id} 空、{@code project_id} 非空）随项目可见性；</li>
     *   <li><b>点专属规则</b>（{@code point_id} 非空）随测点可见性——以点为准，不看 {@code project_id}。</li>
     * </ol>
     *
     * <p>全局规则刻意不藏：它同时作用于所有项目，某一类警情为什么触发，
     * 值班员看不到规则就无从解释。规则本体不含项目业务数据（只有阈值与等级）。</p>
     *
     * <p>项目档与点档**必须**在本方法里挡住：V22 之前所有规则都是全局的，过滤只需看
     * {@code point_id}；现在「某项目一套阈值」本身就可能暴露该项目的存在与结构类型，
     * 如果这里不挡，改完作用域只是把误报换成了越权可见。</p>
     *
     * <p>空集合的坑与 {@link #applyAlarmScope} 同源：{@code in(空集合)} 会丢掉整条条件，
     * 于是「没有可见项目」会退化成「所有项目规则都可见」。三组条件都只在集合非空时才挂，
     * 全空时只剩全局那一组——那正是应该的结果。</p>
     */
    public LambdaQueryWrapper<AlarmRule> alarmRuleFilter() {
        Set<Long> pointIds = visiblePointIdsOrNull();
        if (pointIds == null) {
            return null;
        }
        Set<Long> projectIds = visibleProjectIdsOrNull();
        LambdaQueryWrapper<AlarmRule> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> {
            w.isNull(AlarmRule::getPointId).isNull(AlarmRule::getProjectId);
            if (projectIds != null && !projectIds.isEmpty()) {
                w.or(x -> x.isNull(AlarmRule::getPointId).in(AlarmRule::getProjectId, projectIds));
            }
            if (!pointIds.isEmpty()) {
                w.or(x -> x.in(AlarmRule::getPointId, pointIds));
            }
        });
        return wrapper;
    }

    // ==================== 内部 ====================

    /**
     * 按 id 集合建条件；<b>空集合短路成 {@code 1 = 0}</b>。
     *
     * <p>为什么不用 {@code in(col, ids)} 一把梭：MyBatis-Plus 遇到空集合会把整个条件丢掉，
     * 结果是「查不到任何可见项目」变成「不限制项目」。写成 {@code 1 = 0} 才是这两者之间
     * 唯一诚实的表达。id 都是正数，本来也可以用 {@code eq(col, -1L)}，但那个哨兵值
     * 需要读者先知道「id 不会是 -1」，{@code 1 = 0} 不需要任何前提。</p>
     */
    private static <T> LambdaQueryWrapper<T> inIds(SFunction<T, ?> column, Collection<Long> ids) {
        LambdaQueryWrapper<T> wrapper = new LambdaQueryWrapper<>();
        if (ids.isEmpty()) {
            return wrapper.apply("1 = 0");
        }
        return wrapper.in(column, ids);
    }
}
