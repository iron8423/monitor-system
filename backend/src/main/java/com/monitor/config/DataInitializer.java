package com.monitor.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.monitor.auth.entity.SysUser;
import com.monitor.auth.mapper.SysUserMapper;
import com.monitor.common.constant.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 启动时幂等创建 4 个演示账号（密码需 bcrypt 编码，故不走 Flyway SQL）。
 * 演示密码统一：123456
 *
 * <p>外加第 5 个不属于演示角色的账号 {@code outsider}（数据隔离的对照组，见下）。</p>
 *
 * <p>显示名刻意**不等于角色名**（`陈立` 而不是 `管理员`）：顶栏那个「姓名 + 角色」标签
 * 的渲染条件是 `roleLabel != displayName`（见 `AppLayout.vue`），显示名一旦等于角色名，
 * 条件恒假，标签一次都不会出现。换成真人名后那段代码自然就活了，不用改。</p>
 *
 * <p>{@code @Order(10)}：项目成员种子（{@link ProjectMemberInitializer}，{@code @Order(20)}）
 * 要按用户名查本类创建的账号，必须排在其后。</p>
 *
 * <h3>生产环境必须关掉</h3>
 * <p>本类是<b>演示</b>设施：五个账号、统一密码。生产上它们是一组谁都知道口令的入口，
 * 所以由 {@code monitor.demo-accounts.enabled} 控制，而 <b>{@code postgres} profile 里默认是
 * {@code false}</b>（见 {@code application-postgres.yml}）——生产跑的就是那个 profile，
 * 于是「生产不建演示账号」不依赖任何人记得去设环境变量。
 * {@code matchIfMissing = true} 让默认（H2）profile 与全部验收套件的行为保持不变。</p>
 *
 * <p>dev 的 {@code docker-compose.yml} 走的也是 postgres profile，所以那里显式设了
 * {@code MONITOR_DEMO_ACCOUNTS=true}：安全默认值归 profile，dev 的便利在编排文件里
 * 豁免一次、且写明白。不这么办的话，一台全新的 dev 环境起来后一个账号都没有，
 * 谁都登不进去，而 {@code 10-scope.sh} 还需要其中的 {@code outsider} 做隔离对照组。</p>
 */
@Slf4j
@Component
@Order(10)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "monitor.demo-accounts", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@SuppressWarnings("null")
public class DataInitializer implements CommandLineRunner {

    /**
     * 演示账号的默认密码。用 {@code DEMO_ACCOUNT_PASSWORD} 覆盖，
     * 或直接关掉整个初始化器（生产推荐后者）。
     *
     * <p>下面 {@code @Value} 里的默认值是拼进来的而不是重写一遍字面量——
     * 常量与注解里的默认值必须同步，分两处写迟早会漂移。</p>
     */
    private static final String DEFAULT_PASSWORD = "123456";

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Value("${monitor.demo-accounts.password:" + DEFAULT_PASSWORD + "}")
    private String demoPassword;

    /**
     * 第 5 个账号 {@code outsider} 不是演示角色，是**数据隔离的对照组**：
     * 它没有被加进任何项目（{@code ProjectMemberInitializer} 只加那四个），
     * 所以它是一份「没有可见范围」的活样本。
     *
     * <p>为什么必须真的有一个这样的账号：「可见项目为空」是最容易写反的一处——
     * MyBatis-Plus 的 {@code in(空集合)} 会把条件整条丢掉，于是「什么都看不到」
     * 退化成「所有项目都可见」，而且是静默的 fail-open。这条路径没有账号就走不到，
     * 只能靠推理，而推理在这一点上恰恰不可靠（写完 {@code inIds} 的人当然觉得自己写对了）。</p>
     *
     * <p>刻意<b>不进</b>登录页那张演示账号卡片：那四张是给人演四个角色的，
     * 多一张会让「四个演示角色」这件事变得含糊。它的用途在验收套件
     * （{@code 10-scope.sh}）与「隔离生效长什么样」的排查里。</p>
     */
    private static final String OUTSIDER = "outsider";

    @Override
    public void run(String... args) {
        ensureDemoAccount("admin", "陈立", Role.ADMIN,
                new DemoProfile("监测室主任", "138-0000-0001", "chenli@example.com"));
        ensureDemoAccount("operator", "李敏", Role.OPERATOR,
                new DemoProfile("值班长", "138-0000-0002", "limin@example.com"));
        ensureDemoAccount("analyst", "王越", Role.ANALYST,
                new DemoProfile("监测分析师", "138-0000-0003", "wangyue@example.com"));
        ensureDemoAccount("maintainer", "赵安", Role.MAINTAINER,
                new DemoProfile("设备运维工程师", "138-0000-0004", "zhaoan@example.com"));
        // 对照组的资料刻意留空：个人中心要能看到「空字段显示成 —」这条路径，
        // 而不是所有账号都恰好填满（那会把「没登记」这件事从界面上抹掉）。
        ensureDemoAccount(OUTSIDER, "访客（未加入任何项目）", Role.ANALYST,
                new DemoProfile("访客", null, null));
    }

    /**
     * 演示账号的个人资料（V17 的 phone/email/job_title）。
     *
     * <p>值都是**明显假**的：电话用 {@code 138-0000-00xx}，邮箱用 IANA 保留的
     * {@code example.com}——演示数据一旦长得像真的，就会有人拿去打电话。</p>
     */
    private record DemoProfile(String jobTitle, String phone, String email) {
    }

    /**
     * 账号不存在则整条创建；已存在则**只校正显示名**。
     *
     * <p>为什么不能只是「不存在才建」：显示名是给人看的演示数据，而已经跑起来的库
     * （比如 compose 里那个 PG 数据卷）躺着的是旧值——只改本文件的字面量对它们毫无影响，
     * 换个名字重启一看还是「管理员」。</p>
     *
     * <p>为什么只动显示名：密码与角色都不碰。这两样万一被人调过，启动时按字面量重置回去
     * 就是数据事故，而显示名没有这个风险——全仓除本类外没有任何地方写 `display_name`
     * （也没有用户管理接口），它本来就是这份种子文件的自有字段。</p>
     *
     * <p>副作用：每次启动都会把这四个演示账号的显示名拉回来。可接受——这四个账号密码固定、
     * 无法在界面上编辑，本就不是可改数据。</p>
     */
    private void ensureDemoAccount(String username, String displayName, Role role, DemoProfile profile) {
        // username 上有唯一约束（uk_sys_user_username），selectOne 不会撞多条
        SysUser existing = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (existing != null) {
            boolean changed = false;
            if (!displayName.equals(existing.getDisplayName())) {
                existing.setDisplayName(displayName);
                changed = true;
                log.info("已校正演示账号显示名: {} -> {}", username, displayName);
            }
            // 资料只在**为空**时补：显示名是这份种子文件的自有字段（全仓无第二处写入），
            // 而岗位/电话/邮箱将来会由管理员录入——那些值不该被每次启动按字面量抹掉。
            if (profile != null) {
                if (isBlank(existing.getJobTitle()) && profile.jobTitle() != null) {
                    existing.setJobTitle(profile.jobTitle());
                    changed = true;
                }
                if (isBlank(existing.getPhone()) && profile.phone() != null) {
                    existing.setPhone(profile.phone());
                    changed = true;
                }
                if (isBlank(existing.getEmail()) && profile.email() != null) {
                    existing.setEmail(profile.email());
                    changed = true;
                }
            }
            if (changed) {
                userMapper.updateById(existing);
            }
            return;
        }
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode(demoPassword));
        u.setDisplayName(displayName);
        u.setRole(role.name());
        u.setOrganizationId(1L);
        u.setEnabled(true);
        if (profile != null) {
            u.setJobTitle(profile.jobTitle());
            u.setPhone(profile.phone());
            u.setEmail(profile.email());
        }
        userMapper.insert(u);
        // 不打印口令：日志会进容器日志、日志采集、以及别人贴出来的排障片段。
        log.info("已创建演示账号: {}", username);
        if (DEFAULT_PASSWORD.equals(demoPassword)) {
            log.warn("演示账号 {} 使用默认密码，生产环境请设置 monitor.demo-accounts.enabled=false"
                    + "（postgres profile 已默认关闭），或至少用 DEMO_ACCOUNT_PASSWORD 覆盖", username);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
