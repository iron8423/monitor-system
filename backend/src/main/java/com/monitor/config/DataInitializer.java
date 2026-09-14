package com.monitor.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.monitor.auth.entity.SysUser;
import com.monitor.auth.mapper.SysUserMapper;
import com.monitor.common.constant.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
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
 */
@Slf4j
@Component
@Order(10)
@RequiredArgsConstructor
@SuppressWarnings("null")
public class DataInitializer implements CommandLineRunner {

    public static final String DEFAULT_PASSWORD = "123456";

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

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
        ensureDemoAccount("admin", "陈立", Role.ADMIN);
        ensureDemoAccount("operator", "李敏", Role.OPERATOR);
        ensureDemoAccount("analyst", "王越", Role.ANALYST);
        ensureDemoAccount("maintainer", "赵安", Role.MAINTAINER);
        ensureDemoAccount(OUTSIDER, "访客（未加入任何项目）", Role.ANALYST);
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
    private void ensureDemoAccount(String username, String displayName, Role role) {
        // username 上有唯一约束（uk_sys_user_username），selectOne 不会撞多条
        SysUser existing = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (existing != null) {
            if (!displayName.equals(existing.getDisplayName())) {
                existing.setDisplayName(displayName);
                userMapper.updateById(existing);
                log.info("已校正演示账号显示名: {} -> {}", username, displayName);
            }
            return;
        }
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode(DEFAULT_PASSWORD));
        u.setDisplayName(displayName);
        u.setRole(role.name());
        u.setOrganizationId(1L);
        u.setEnabled(true);
        userMapper.insert(u);
        log.info("已创建演示账号: {}/{}", username, DEFAULT_PASSWORD);
    }
}
