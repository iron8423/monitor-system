package com.monitor;

import com.monitor.common.util.Times;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

/**
 * 监测管理系统后端入口。
 *
 * <p>阶段 1 使用 H2 内存库（零配置），阶段 2 通过 {@code application-postgres.yml} 切 PostgreSQL。</p>
 *
 * <p>{@code @EnableScheduling} 目前供 SSE 心跳使用（{@code SseBroadcaster#heartbeat}）。</p>
 *
 * <p><b>时区必须钉死在 Asia/Shanghai</b>（2026-09-18 由 CI 抓出来的一个真 bug）：
 * {@code Times.iso(t)} 给 {@code LocalDateTime} 挂的是 {@code +08:00}，前提是那些值确实是
 * 上海墙钟时间；而 {@code LocalDateTime.now()} 取的是 **JVM 默认时区**的墙钟。
 * 两者一错开，全平台的时间就都偏了 8 小时——最要命的后果不是显示难看，而是接入侧：
 * 设备按北京时间报"现在"，平台按 UTC 墙钟算"现在"，于是**整批实时数据被判成 8 小时后的未来
 * 而拒收**（{@code COLLECT_TIME_IN_FUTURE}）。容器镜像里有 {@code ENV TZ=Asia/Shanghai}
 * 把这个坑盖住了，但在裸机/CI/k8s（默认 UTC）上一跑就现形。</p>
 *
 * <p>放在 {@code main} 的第一行而不是写进 {@code application.yml}：{@code spring.jackson.time-zone}
 * 只管 Jackson 序列化，管不到 {@code LocalDateTime.now()}；而 {@code TZ} 是部署侧配置，
 * 本应用**明确只服务一个时区的业务**（见 {@link Times#ZONE}），把这条前提写进代码才不会被漏配。</p>
 */
@Slf4j
@SpringBootApplication
@EnableScheduling
public class MonitorApplication {

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone(Times.ZONE));
        log.info("JVM 默认时区已钉定为 {}（平台时间口径的唯一基准）", TimeZone.getDefault().getID());
        SpringApplication.run(MonitorApplication.class, args);
    }
}
