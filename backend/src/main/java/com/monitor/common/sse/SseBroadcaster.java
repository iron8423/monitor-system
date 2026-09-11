package com.monitor.common.sse;

import com.monitor.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 广播中心（《B侧接口契约_M0》§6）：把接入的测值和警情实时推给驾驶舱。
 *
 * <p>三条硬约束：</p>
 * <ol>
 *   <li><b>不阻断主流程</b>——推送失败只摘连接，绝不把异常抛回 ingest / 告警评估；</li>
 *   <li><b>不推幽灵事件</b>——若在事务中调用，挂到 {@code afterCommit} 再发，回滚了就不发；</li>
 *   <li><b>不无限增长</b>——订阅数封顶 + 超时/心跳回收，避免断连堆积。</li>
 * </ol>
 */
@Slf4j
@Component
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

    private final Map<String, SseEmitter> clients = new ConcurrentHashMap<>();

    /** 建立订阅。超出上限直接拒绝，避免耗尽容器线程。 */
    public SseEmitter subscribe() {
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
        clients.put(id, emitter);

        try {
            emitter.send(SseEmitter.event().name(EVENT_CONNECTED).data(Map.of("clientId", id)));
        } catch (Exception e) {
            clients.remove(id);
            throw new BizException("SSE 订阅失败: " + e.getMessage());
        }
        log.debug("SSE 订阅建立 clientId={} 在线 {}", id, clients.size());
        return emitter;
    }

    /**
     * 广播事件。处于事务中时挂到提交后执行——否则事务回滚会推出「幽灵事件」，
     * 前端已经画上去了但库里没有。
     */
    public void broadcast(String event, Object payload) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doBroadcast(event, payload);
                }
            });
            return;
        }
        doBroadcast(event, payload);
    }

    private void doBroadcast(String event, Object payload) {
        if (clients.isEmpty()) {
            return;
        }
        for (Map.Entry<String, SseEmitter> entry : clients.entrySet()) {
            try {
                entry.getValue().send(SseEmitter.event().name(event).data(payload));
            } catch (Exception e) {
                // 客户端已断开：摘掉自己，不影响其他订阅者
                clients.remove(entry.getKey());
                try {
                    entry.getValue().complete();
                } catch (Exception ignored) {
                    // 已经坏了，complete 再失败也无所谓
                }
            }
        }
    }

    /** 心跳：注释帧（以 ':' 开头）不会触发前端的 message 事件，只用于保活。 */
    @Scheduled(fixedDelay = HEARTBEAT_MS)
    public void heartbeat() {
        for (Map.Entry<String, SseEmitter> entry : clients.entrySet()) {
            try {
                entry.getValue().send(SseEmitter.event().comment("ping"));
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
