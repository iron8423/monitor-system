package com.monitor.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.monitor.auth.entity.SysUser;
import com.monitor.auth.mapper.SysUserMapper;
import com.monitor.project.entity.Project;
import com.monitor.project.mapper.ProjectMapper;
import com.monitor.scope.entity.ProjectMember;
import com.monitor.scope.mapper.ProjectMemberMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 项目成员种子（V8 建表 + 项目 2，成员关系在这里补）。
 *
 * <p><b>为什么成员关系不能写进 V8 迁移</b>：演示账号是 {@link DataInitializer} 在启动时创建的
 * （密码要 bcrypt 编码），而 Flyway 跑在 {@code CommandLineRunner} <b>之前</b>——
 * 那一刻 {@code sys_user} 还是空表，迁移里写 {@code user_id} 只能是悬空外键，
 * 或者硬编码 1~4 而那些 id 在新库上还不存在。</p>
 *
 * <p>种子内容：四个演示账号都进项目 1（不加的话，数据范围一生效谁都看不见那 7 个测点，
 * 演示直接变成一片空白）；项目 2（另一组织的采空区）<b>只加 admin</b>——
 * 这样「李敏看不到项目 B」才是真的能演的一件事，而不是一句设计说明。</p>
 *
 * <p>{@code @Order(20)} 必须大于 {@link DataInitializer} 的 10：本类按用户名查用户，
 * 跑在账号创建之前会一条也查不到，然后**因为幂等而在后续每次启动都同样查不到**
 * （第一次启动静默无成员，第二次启动才补上）——这种「第一次是坏的」最难查。</p>
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
@SuppressWarnings("null")
public class ProjectMemberInitializer implements CommandLineRunner {

    /** 项目 1（清远电厂灰库/库区边坡），四个演示角色都在里面。 */
    private static final String DEMO_PROJECT_CODE = "PRJ-QY-HK";

    /** 项目 2（西江水泥采空区），只给 admin，用来演示隔离。 */
    private static final String OTHER_PROJECT_CODE = "PRJ-XJ-CKQ";

    private static final List<String> DEMO_USERS = List.of("admin", "operator", "analyst", "maintainer");

    private final ProjectMemberMapper memberMapper;
    private final SysUserMapper userMapper;
    private final ProjectMapper projectMapper;

    @Override
    public void run(String... args) {
        Long demoProject = projectIdOf(DEMO_PROJECT_CODE);
        Long otherProject = projectIdOf(OTHER_PROJECT_CODE);
        if (demoProject == null || otherProject == null) {
            // 迁移没跑到就说明启动本身有问题，这里只出声不抛——启动失败由 Flyway 自己报
            log.warn("项目成员种子跳过：找不到项目 {} / {}", DEMO_PROJECT_CODE, OTHER_PROJECT_CODE);
            return;
        }
        for (String username : DEMO_USERS) {
            ensureMember(username, demoProject);
        }
        ensureMember("admin", otherProject);
    }

    private Long projectIdOf(String code) {
        Project p = projectMapper.selectOne(
                new LambdaQueryWrapper<Project>().eq(Project::getCode, code));
        return p == null ? null : p.getId();
    }

    /** 幂等：已经在里面就什么都不做（重启不改变已有成员关系）。 */
    private void ensureMember(String username, Long projectId) {
        SysUser user = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (user == null) {
            log.warn("项目成员种子跳过：账号不存在 {}", username);
            return;
        }
        Long exists = memberMapper.selectCount(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getUserId, user.getId())
                .eq(ProjectMember::getProjectId, projectId));
        if (exists != null && exists > 0) {
            return;
        }
        ProjectMember member = new ProjectMember();
        member.setUserId(user.getId());
        member.setProjectId(projectId);
        memberMapper.insert(member);
        log.info("已加入项目成员: {} -> project {}", username, projectId);
    }
}
