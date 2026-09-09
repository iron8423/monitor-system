package com.monitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 监测管理系统后端入口。
 *
 * <p>阶段 1 使用 H2 内存库（零配置），阶段 2 通过 {@code application-postgres.yml} 切 PostgreSQL。</p>
 */
@SpringBootApplication
public class MonitorApplication {

    public static void main(String[] args) {
        SpringApplication.run(MonitorApplication.class, args);
    }
}
