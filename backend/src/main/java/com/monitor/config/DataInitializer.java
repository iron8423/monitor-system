package com.monitor.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.monitor.auth.entity.SysUser;
import com.monitor.auth.mapper.SysUserMapper;
import com.monitor.common.constant.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 启动时幂等创建 4 个演示账号（密码需 bcrypt 编码，故不走 Flyway SQL）。
 * 演示密码统一：123456
 */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressWarnings("null")
public class DataInitializer implements CommandLineRunner {

    public static final String DEFAULT_PASSWORD = "123456";

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        createIfAbsent("admin", "管理员", Role.ADMIN);
        createIfAbsent("operator", "值班员", Role.OPERATOR);
        createIfAbsent("analyst", "研判员", Role.ANALYST);
        createIfAbsent("maintainer", "运维员", Role.MAINTAINER);
    }

    private void createIfAbsent(String username, String displayName, Role role) {
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (count != null && count > 0) {
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
