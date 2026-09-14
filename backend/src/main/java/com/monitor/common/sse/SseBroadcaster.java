package com.monitor.common.sse;

import com.monitor.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * SSE 广播中心（《B侧接口契约_M0》§6）：把接入的测值和警情实时推给驾驶舱。
 *
 * <p>四条硬约束：</p>
 * <ol>
 *   <li><b>不阻断主流程</b>——推送失败只摘连接，绝不把异常抛回 ingest / 告警评估；</li>
 *   <li><b>不推幽灵事件</b>——若在事务中调用，挂到 {@code afterCommit} 再发，回滚了就不发；</li>
 *   <li><b>不无限增长</b>——订阅数封顶 + 超时/心跳回收，避免断连堆积；</li>
 *   <li><b>不越过数据范围</b>——按订阅者过滤（见下）。</li>
 * </ol>
 *
 * <h3>为什么推送必须按订阅者过滤</h3>
 * <p>项目数据范围隔离（验收第 7 条）把 {@code /points}、{@code /devices} 这些<b>查询</b>限到了
 * 「当前用户是成员的项目」，但推送原先仍推全库——于是出现「界面上根本看不到这个测点，
 * 告警横幅却把它弹了出来」，而且别的项目的 {@code pointCode / deviceCode} 会到达
 * 不该看到的浏览器。这是隔离被<b>绕过</b>的一条路，不是显示问题。</p>
 *
 * <p>过滤判据与 HTTP 侧完全同一份（{@link SubscriberScope} → {@code DataScopeService}），
 * 即「订阅者可见的项目」与「事件归属的项目」有交集才发。三处刻意的选择：</p>
 * <ul>
 *   <li>事件归属是<b>集合</b>不是单值：一台设备可以绑多个项目的测点
 *       （{@code device_point} 没有唯一约束），这时它的设备告警对这些项目都可见——
 *       与 HTTP 侧 {@code canSeeDevice} 的「任一命中」保持一致。</li>
 *   <li>归属为<b>空集</b>（如还没绑测点的新设备）→ 只发 ADMIN。与 CRUD 侧
 *       「无归属记录仅管理员可见」同一条规则，fail-closed。</li>
 *   <li><b>没有「广播给所有人」这个入口</b>：全部广播都必须给得出项目归属，
 *       于是没有哪条代码路径能不小心绕开过滤。将来真出现全局事件再加。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressWarnings("null")
public class SseBroadcaster {

    public static final String EVENT_CONNECTED = "connected";
    public static final String EVENT_MEASUREMENT = "measurement";
    public static final String EVENT_ALARM = "alarm";

    /** 单连接超时（毫秒）：到点由容器关闭，EventSource 会自动重连。 */
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    /** 同时在线订阅者上限——连接是内存态，需有上限兜底。 */
    private static final int MAX_CLIENTS = 100;

    /** 心跳间隔（毫秒）：空闲连接会被中间层掐断，用注释帧探活。 */
    private static final long HEARTBEAT_MS = 30_000L;

    private final SubscriberScope subscriberScope;

    /** 一个订阅者 = 一条连接 + 它是谁。**身份必须在这里存下来**：发事件时没有 HTTP 请求，
     *  {@code SecurityContextHolder} 是空的，取不到「现在是谁在听」。 */
    private record Client(SseEmitter emitter, Long userId, String role) {
    }

    private final Map<String, Client> clients = new ConcurrentHashMap<>();

    /** 建立订阅。超出上限直接拒绝，避免耗尽容器线程。 */
    public SseEmitter subscribe(Long userId, String role) {
        if (clients.size() >= MAX_CLIENTS) {
            throw new BizException("SSE 订阅数已达上限 " + MAX_CLIENTS);
        }
        String id = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitter.onCompletion(() -> clients.remove(id));
        emitter.onTimeout(() -> {
            clients.remove(id);
            emitter.complete();
        });
        emitter.onError(e -> clients.remove(id));
        clients.put(id, new Client(emitter, userId, role));

        try {
            emitter.send(SseEmitter.event().name(EVENT_CONNECTED).data(Map.of("clientId", id)));
        } catch (Exception e) {
            clients.remove(id);
            throw new BizException("SSE 订阅失败: " + e.getMessage());
        }
        log.debug("SSE 订阅建立 clientId={} userId={} role={} 在线 {}", id, userId, role, clients.size());
        return emitter;
    }

    /**
     * 按数据范围广播事件。处于事务中时挂到提交后执行——否则事务回滚会推出「幽灵事件」，
     * 前端已经画上去了但库里没有。
     *
     * @param projectIds 事件归属的项目 id，**惰性求值**：没有任何订阅者、或订阅者全是 ADMIN 时
     *                   一次查询都不发。取值可能发生在事务提交之后，那是读操作，没有问题。
     *                   <b>返回空集表示「无归属」而不是「不限制」</b>，只发给 ADMIN。
     */
    public void broadcastScoped(String event, Object payload, Supplier<Set<Long>> projectIds) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doBroadcastScoped(event, payload, projectIds);
                }
            });
            return;
        }
        doBroadcastScoped(event, payload, projectIds);
    }

    private void doBroadcastScoped(String event, Object payload, Supplier<Set<Long>> projectIds) {
        if (clients.isEmpty()) {
            return;
        }
        Set<Long> eventProjects = null;
        boolean resolved = false;
        for (Map.Entry<String, Client> entry : clients.entrySet()) {
            Client client = entry.getValue();
            Set<Long> visible = subscriberScope.visibleProjectIdsOf(client.userId(), client.role());
            boolean deliver;
            if (visible == null) {
                deliver = true;                       // ADMIN：不受限
            } else {
                if (!resolved) {
                    eventProjects = projectIds.get();
                    resolved = true;
                }
                deliver = !Collections.disjoint(visible, eventProjects);
            }
            if (deliver) {
                send(entry.getKey(), client, event, payload);
            }
        }
    }

    private void send(String id, Client client, String event, Object payload) {
        try {
            client.emitter().send(SseEmitter.event().name(event).data(payload));
        } catch (Exception e) {
            // 客户端已断开：摘掉自己，不影响其他订阅者
            clients.remove(id);
            try {
                client.emitter().complete();
            } catch (Exception ignored) {
                // 已经坏了，complete 再失败也无所谓
            }
        }
    }

    /** 心跳：注释帧（以 ':' 开头）不会触发前端的 message 事件，只用于保活。 */
    @Scheduled(fixedDelay = HEARTBEAT_MS)
    public void heartbeat() {
        for (Map.Entry<String, Client> entry : clients.entrySet()) {
            try {
                entry.getValue().emitter().send(SseEmitter.event().comment("ping"));
            } catch (Exception e) {
                clients.remove(entry.getKey());
            }
        }
    }

    /** 当前订阅数（观测用）。 */
    public int clientCount() {
        return clients.size();
    }
}
