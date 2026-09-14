package com.monitor.common.sse;

import java.util.Set;

/**
 * 订阅者的可见范围（实现类见 {@code DataScopeService}）。
 *
 * <p>接口放在 {@code common.sse} 而不是让广播中心直接依赖 {@code scope.service}：
 * {@code common} 是被依赖层，反过来依赖 {@code scope} 会让层次倒置，
 * 而广播中心只需要的其实是「这个人能看哪些项目」这一件事。
 * 与 HTTP 侧的 {@code DataScopeService} 共用同一份判据——两处各写一套的话，
 * 「查询被挡住、推送却漏出去」就会重新出现，而那正是本次要修的问题。</p>
 */
public interface SubscriberScope {

    /**
     * 该订阅者可见的项目 id。
     *
     * @return {@code null} 表示<b>不受限</b>（ADMIN），不是「没有可见项目」——
     *         与 {@code DataScopeService} 里各 {@code visibleXxxIdsOrNull} 同一约定。
     *         两者混起来的话，管理员会收不到任何推送。
     */
    Set<Long> visibleProjectIdsOf(Long userId, String role);
}
