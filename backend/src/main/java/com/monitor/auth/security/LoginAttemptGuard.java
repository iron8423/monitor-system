package com.monitor.auth.security;

import com.monitor.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败限流（复查清单 P1-14 的一半）。
 *
 * <p>此前登录接口**没有任何节流**：一个脚本可以把口令表按每秒几千次往 /auth/login 上打，
 * 而平台这边既不拦也不记。BCrypt 让每次尝试慢一些，但那是"成本"，不是"防线"。</p>
 *
 * <h3>口径</h3>
 * <ul>
 *   <li><b>按「来源 IP + 账号」计数</b>，不是只按 IP：只按 IP 会让同一个出口 NAT 后的
 *       正常用户被邻居的爆破连坐；只按账号则会让攻击者用一个 IP 打一个账号、
 *       换个 IP 接着打（成本极低）。两个维度一起锁，代价是同一 IP 换账号仍可试——
 *       那由账号维度兜住。</li>
 *   <li><b>只数失败</b>：成功即清零。正常用户敲错两次再敲对，不该背着两次记录。</li>
 *   <li><b>窗口 5 分钟、阈值 5 次</b>（都可配：{@code monitor.auth.login-max-failures} /
 *       {@code monitor.auth.login-lock-seconds}），到点自动放行。</li>
 * </ul>
 *
 * <h3>两条必须说清的边界</h3>
 * <ol>
 *   <li><b>计数在进程内存里</b>：多实例部署时每个实例各算各的，攻击者轮着打可以放宽
 *       N 倍（N = 实例数）。要做到跨实例精确，需要 Redis 或库表——本仓当前是单实例
 *       编排（见 docker-compose.production.yml），在这里引入一个外部依赖不划算。
 *       这条限制写在这里，是为了将来扩容时**有人会想起来**。</li>
 *   <li><b>它挡的是"猜口令"，不是"打流量"</b>：真正的抗 DoS 在网关/限流层。
 *       这里的目标是让口令爆破从"几小时"变成"不现实"。</li>
 * </ol>
 */
@Slf4j
@Component
public class LoginAttemptGuard {

    /** 超过这个 key 数量就做一次过期清理：随机用户名会让 map 无限长大（内存放大攻击面）。 */
    private static final int CLEANUP_THRESHOLD = 20000;

    @Value("${monitor.auth.login-max-failures:5}")
    private int maxFailures;

    @Value("${monitor.auth.login-lock-seconds:300}")
    private long lockSeconds;

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    /** 一次失败记录：窗口起点 + 窗口内失败次数。 */
    private record Attempt(Instant firstAt, int count) {
    }

    /**
     * 已锁定就抛 429（带还需要等多少秒），否则放行。
     *
     * <p>用 429 而不是 401：401 是"这次凭据不对"，429 是"别再试了"。
     * 分不清这两者时，客户端会以为"再试一次可能就对了"而继续打。</p>
     */
    public void assertNotLocked(String username) {
        String key = key(username);
        if (key == null) {
            return;
        }
        Attempt attempt = attempts.get(key);
        if (attempt == null) {
            return;
        }
        long elapsed = Duration.between(attempt.firstAt(), Instant.now()).toSeconds();
        if (elapsed >= lockSeconds) {
            attempts.remove(key, attempt);   // 窗口过期：放行并清掉，别让旧记录把下一次也算上
            return;
        }
        if (attempt.count() >= maxFailures) {
            long wait = lockSeconds - elapsed;
            throw new BizException(429, "登录失败次数过多（" + attempt.count() + "/" + maxFailures
                    + "），请 " + wait + " 秒后再试");
        }
    }

    /** 记一次失败。窗口内累加；窗口已过则从这一次重新开始计数。 */
    public void recordFailure(String username) {
        String key = key(username);
        if (key == null) {
            return;
        }
        Instant now = Instant.now();
        attempts.compute(key, (k, old) -> {
            if (old == null || Duration.between(old.firstAt(), now).toSeconds() >= lockSeconds) {
                return new Attempt(now, 1);
            }
            return new Attempt(old.firstAt(), old.count() + 1);
        });
        if (attempts.size() > CLEANUP_THRESHOLD) {
            purgeExpired();
        }
    }

    /** 登录成功：清零。记录只是"最近失败过"，不该跟着正常用户一辈子。 */
    public void recordSuccess(String username) {
        String key = key(username);
        if (key != null) {
            attempts.remove(key);
        }
    }

    /** 给套件与运维看用的只读探针（当前是否锁定、已失败几次）。 */
    public int failureCount(String username) {
        Attempt attempt = attempts.get(key(username));
        if (attempt == null) {
            return 0;
        }
        if (Duration.between(attempt.firstAt(), Instant.now()).toSeconds() >= lockSeconds) {
            return 0;
        }
        return attempt.count();
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        attempts.entrySet().removeIf(e -> Duration.between(e.getValue().firstAt(), now).toSeconds() >= lockSeconds);
    }

    /**
     * 键 = 来源 IP + 账号。IP 取 {@code X-Forwarded-For} 的第一跳
     * （前端 nginx 会写上，见 frontend/nginx.conf），否则取直连地址。
     * 无请求上下文（例如单测里直接调 service）时返回 {@code null} = **不限流**：
     * 拿不到来源就计数，会把所有调用者算成同一个人。
     */
    private String key(String username) {
        String user = username == null ? "" : username.trim().toLowerCase();
        String ip = clientIp();
        if (ip == null) {
            return null;
        }
        return ip + "|" + user;
    }

    private static String clientIp() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        HttpServletRequest request = attrs.getRequest();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
