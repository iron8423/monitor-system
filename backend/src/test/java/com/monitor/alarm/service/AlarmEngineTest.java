package com.monitor.alarm.service;

import com.monitor.alarm.AlarmConstants;
import com.monitor.alarm.entity.Alarm;
import com.monitor.alarm.entity.AlarmAction;
import com.monitor.alarm.entity.AlarmRule;
import com.monitor.alarm.mapper.AlarmActionMapper;
import com.monitor.alarm.mapper.AlarmMapper;
import com.monitor.alarm.mapper.AlarmRuleMapper;
import com.monitor.common.concurrent.KeyLock;
import com.monitor.common.sse.SseBroadcaster;
import com.monitor.config.AlarmEvalTx;
import com.monitor.project.mapper.MonitorPointMapper;
import com.monitor.scope.service.DataScopeService;
import com.monitor.support.MybatisPlusLambdaCache;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 清单第 32 条：告警评估的三层并发控制（{@code KeyLock} 串行 / {@code AlarmEvalTx} 短事务 /
 * {@code AlarmMapper} 的 CAS）以及 V14 未解除唯一键的**写入侧**契约。
 *
 * <p>这里能测的不是「并发下会不会重复」——那是 {@code AlarmConcurrencySemanticsTest} 与
 * 验收套件里真并发用例的事。这里要钉住的是三件单测就能说清、且**改坏了不会有别的测试报警**的事：</p>
 * <ol>
 *   <li><b>纯读路径不碰锁、不开事务</b>。这不是优化洁癖：写入路径开的是
 *       {@code REQUIRES_NEW}，而外层接入事务此时正握着一条连接，无脑开事务会让一个接入线程
 *       占两条连接，Hikari 默认池 10 在 5 个并发接入下就打满。这条断言一旦失效，
 *       表现是「压测时接入超时」，而不是任何一条功能用例失败。</li>
 *   <li><b>新开的警情必须带上测项副本与未解除键</b>，且两者拼法与 V14 迁移一致。
 *       漏了 {@code openKey} 不会报错——唯一索引对多个 NULL 是放行的，于是并发重复警情
 *       照旧出现，正是这一条要防的。</li>
 *   <li><b>升级与解除走 CAS，且 CAS 落空时不写时间线</b>。落空还写 {@code escalate} 留痕的话，
 *       时间线里会出现一次实际没发生过的等级变化，比漏记更难排查。</li>
 * </ol>
 */
class AlarmEngineTest {

    private static final Long POINT_ID = 1L;
    private static final String METRIC = "defo_mm";

    private AlarmRuleMapper ruleMapper;
    private AlarmMapper alarmMapper;
    private AlarmActionMapper actionMapper;
    private MonitorPointMapper pointMapper;
    private SseBroadcaster broadcaster;
    private DataScopeService dataScope;
    private KeyLock keyLock;
    private AlarmEvalTx alarmEvalTx;
    private AlarmEngine engine;

    @BeforeAll
    static void warmLambdaCaches() {
        // 纯 Mockito 单测没有 MyBatis-Plus 启动流程，wrapper 的列名解析会抛
        // "can not find lambda cache for this entity"。见 MybatisPlusLambdaCache。
        MybatisPlusLambdaCache.warm(Alarm.class, AlarmRule.class);
    }

    @BeforeEach
    void setUp() {
        ruleMapper = mock(AlarmRuleMapper.class);
        alarmMapper = mock(AlarmMapper.class);
        actionMapper = mock(AlarmActionMapper.class);
        pointMapper = mock(MonitorPointMapper.class);
        broadcaster = mock(SseBroadcaster.class);
        dataScope = mock(DataScopeService.class);
        keyLock = mock(KeyLock.class);
        alarmEvalTx = mock(AlarmEvalTx.class);

        // 让两个协作组件「透传」：单测关心的是引擎的**决策**（要不要加锁/开事务），
        // 不是它们内部怎么实现（那由 KeyLockTest 与数据库层用例覆盖）。
        when(keyLock.runLocked(any(), anyLong(), any())).thenAnswer(inv -> {
            inv.getArgument(2, Runnable.class).run();
            return true;
        });
        doAnswer(inv -> {
            inv.getArgument(0, Runnable.class).run();
            return null;
        }).when(alarmEvalTx).run(any());

        engine = new AlarmEngine(ruleMapper, alarmMapper, actionMapper, pointMapper,
                broadcaster, dataScope, keyLock, alarmEvalTx);
    }

    // ---------- ① 纯读路径 ----------

    /**
     * 值没到阈值、也没有未解除警情 → 什么都不该发生，尤其<b>不该加锁、不该开事务</b>。
     * 这条保护的是接入的连接开销，不是功能。
     */
    @Test
    void readOnlyEvaluationTakesNoLockAndNoTransaction() {
        AlarmRule rule = rule(10L, "warning", "5", "3");
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule));
        when(alarmMapper.selectOne(any())).thenReturn(null);

        engine.evaluate(POINT_ID, METRIC, 1.0, "VALID");

        verifyNoInteractions(keyLock);
        verifyNoInteractions(alarmEvalTx);
        verify(alarmMapper, never()).insert(any(Alarm.class));
        verify(actionMapper, never()).insert(any(AlarmAction.class));
    }

    /** 未到触发阈值但**已有**未解除警情、且值也没到恢复阈值 → 仍不该写（预判必须足够窄，否则白开事务）。 */
    @Test
    void openAlarmWithoutRecoveryOrEscalationIsAlsoReadOnly() {
        AlarmRule rule = rule(10L, "warning", "5", "3");
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule));
        when(alarmMapper.selectOne(any())).thenReturn(openAlarm(100L, 10L, "warning", "PENDING"));

        engine.evaluate(POINT_ID, METRIC, 4.0, "VALID");     // 4 < 5 不触发；4 > 3 不恢复

        verifyNoInteractions(keyLock);
        verifyNoInteractions(alarmEvalTx);
        verify(alarmMapper, never()).insert(any(Alarm.class));
    }

    // ---------- ② 触发 ----------

    @Test
    void triggerWritesMetricCodeAndOpenKey() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule(10L, "warning", "5", "3")));
        when(alarmMapper.selectOne(any())).thenReturn(null);

        engine.evaluate(POINT_ID, METRIC, 9.0, "VALID");

        Alarm inserted = captureInsertedAlarm();
        assertEquals(AlarmConstants.TYPE_POINT, inserted.getAlarmType());
        assertEquals(POINT_ID, inserted.getPointId());
        assertEquals(10L, inserted.getRuleId());
        assertEquals(METRIC, inserted.getMetricCode(), "测项副本必须落库，否则未解除唯一性无从表达");
        assertEquals("P:1:defo_mm", inserted.getOpenKey(),
                "未解除键必须与 V14 迁移拼法一致；漏写的话唯一索引对 NULL 放行，并发重复照旧");
        assertEquals(AlarmConstants.PENDING, inserted.getStatus());
        assertEquals("warning", inserted.getAlarmLevel());
        assertEquals(0, new BigDecimal("9.0").compareTo(inserted.getTriggerValue()));

        // 时间线留痕 + 广播
        ArgumentCaptor<AlarmAction> action = ArgumentCaptor.forClass(AlarmAction.class);
        verify(actionMapper).insert(action.capture());
        assertEquals(AlarmConstants.ACTION_TRIGGER, action.getValue().getActionType());
        assertEquals(AlarmConstants.SYSTEM, action.getValue().getOperator());
    }

    /** 触发路径必须**在锁内、且在独立短事务里**——锁跨不过提交等于没加（见类注释）。 */
    @Test
    void triggerRunsInsideLockAndShortTransaction() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule(10L, "warning", "5", "3")));
        when(alarmMapper.selectOne(any())).thenReturn(null);

        engine.evaluate(POINT_ID, METRIC, 9.0, "VALID");

        verify(keyLock).runLocked(eq("P:1:defo_mm"), anyLong(), any());
        verify(alarmEvalTx).run(any());
    }

    // ---------- ③ 升级 ----------

    /** 已有警情 + 更高等级规则命中 → 就地升级走 CAS，前置条件带**当前**等级。 */
    @Test
    void escalateUsesCasWithCurrentLevel() {
        Alarm open = openAlarm(100L, 10L, "warning", AlarmConstants.PENDING);
        when(ruleMapper.selectList(any()))
                .thenReturn(List.of(rule(10L, "warning", "5", "3"), rule(11L, "alarm", "8", null)));
        when(alarmMapper.selectOne(any())).thenReturn(open);
        when(alarmMapper.escalateAlarm(any(), any(), any(), any(), any())).thenReturn(1);

        engine.evaluate(POINT_ID, METRIC, 9.0, "VALID");

        verify(alarmMapper).escalateAlarm(eq(100L), eq(AlarmConstants.PENDING), eq("warning"),
                eq("alarm"), any(LocalDateTime.class));
        ArgumentCaptor<AlarmAction> action = ArgumentCaptor.forClass(AlarmAction.class);
        verify(actionMapper).insert(action.capture());
        assertEquals(AlarmConstants.ACTION_ESCALATE, action.getValue().getActionType());
        // 升级不新开警情
        verify(alarmMapper, never()).insert(any(Alarm.class));
    }

    /**
     * CAS 落空（等级或状态已被别处改动）→ 不写时间线、不广播。
     * <p>没有这条断言的话，「留痕写在 CAS 之前」这种顺序错误不会被任何用例发现。</p>
     */
    @Test
    void escalateSkippedWhenCasAffectsNoRow() {
        when(ruleMapper.selectList(any()))
                .thenReturn(List.of(rule(10L, "warning", "5", "3"), rule(11L, "alarm", "8", null)));
        when(alarmMapper.selectOne(any())).thenReturn(openAlarm(100L, 10L, "warning", AlarmConstants.PENDING));
        when(alarmMapper.escalateAlarm(any(), any(), any(), any(), any())).thenReturn(0);

        engine.evaluate(POINT_ID, METRIC, 9.0, "VALID");

        verify(actionMapper, never()).insert(any(AlarmAction.class));
        verify(broadcaster, never()).broadcastScoped(any(), any(), any());
    }

    // ---------- ④ 解除 ----------

    /** 回落至恢复阈值内 → 关闭走 {@code closeAlarm}（只有它会写 {@code open_key = NULL}）。 */
    @Test
    void recoverUsesCloseAlarm() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule(10L, "warning", "5", "3")));
        when(alarmMapper.selectOne(any())).thenReturn(openAlarm(100L, 10L, "warning", AlarmConstants.PENDING));
        when(alarmMapper.closeAlarm(any(), any(), any(), any(), any())).thenReturn(1);

        engine.evaluate(POINT_ID, METRIC, 2.0, "VALID");

        verify(alarmMapper).closeAlarm(eq(100L), eq(AlarmConstants.PENDING),
                eq(AlarmConstants.RESOLVED), any(LocalDateTime.class), any(LocalDateTime.class));
        ArgumentCaptor<AlarmAction> action = ArgumentCaptor.forClass(AlarmAction.class);
        verify(actionMapper).insert(action.capture());
        assertEquals(AlarmConstants.ACTION_RECOVER, action.getValue().getActionType());
    }

    @Test
    void recoverSkippedWhenCasAffectsNoRow() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule(10L, "warning", "5", "3")));
        when(alarmMapper.selectOne(any())).thenReturn(openAlarm(100L, 10L, "warning", AlarmConstants.PENDING));
        when(alarmMapper.closeAlarm(any(), any(), any(), any(), any())).thenReturn(0);

        engine.evaluate(POINT_ID, METRIC, 2.0, "VALID");

        verify(actionMapper, never()).insert(any(AlarmAction.class));
        verify(broadcaster, never()).broadcastScoped(any(), any(), any());
    }

    // ---------- ⑤ 既有闸门不被改坏 ----------

    /** 质量不可信的数据不参与判定（message-contract §3）——连规则都不该查。 */
    @Test
    void badQualityIsNotEvaluatedAtAll() {
        engine.evaluate(POINT_ID, METRIC, 99.0, "SUSPECT");
        engine.evaluate(POINT_ID, METRIC, 99.0, "FAULT");

        verifyNoInteractions(ruleMapper, alarmMapper, keyLock, alarmEvalTx);
    }

    /** 评估异常一律吞掉：接入是主流程，不能因为告警判定失败而回滚落库。 */
    @Test
    void exceptionsAreSwallowed() {
        when(ruleMapper.selectList(any())).thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> engine.evaluate(POINT_ID, METRIC, 9.0, "VALID"));
    }

    /** 非 THRESHOLD 规则被忽略，且**不因此少查一次**——忽略但不能静默。 */
    @Test
    void nonThresholdRulesAreIgnored() {
        AlarmRule rate = rule(12L, "warning", "5", null);
        rate.setRuleType("RATE");
        when(ruleMapper.selectList(any())).thenReturn(List.of(rate));
        when(alarmMapper.selectOne(any())).thenReturn(null);

        engine.evaluate(POINT_ID, METRIC, 9.0, "VALID");

        verify(alarmMapper, never()).insert(any(Alarm.class));
        verifyNoInteractions(keyLock);
    }

    /** 同一测点不同测项各自成警情：另一个测项的未解除警情不该挡住本测项触发。 */
    @Test
    void differentMetricDoesNotBlockTrigger() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule(10L, "warning", "5", "3")));
        // findOpen 按 (pointId, metricCode) 查，本测项没有未解除警情
        when(alarmMapper.selectOne(any())).thenReturn(null);

        engine.evaluate(POINT_ID, "rate_mm_d", 9.0, "VALID");

        Alarm inserted = captureInsertedAlarm();
        assertEquals("P:1:rate_mm_d", inserted.getOpenKey());
        assertEquals("rate_mm_d", inserted.getMetricCode());
    }

    // ---------- 辅助 ----------

    private Alarm captureInsertedAlarm() {
        ArgumentCaptor<Alarm> captor = ArgumentCaptor.forClass(Alarm.class);
        verify(alarmMapper).insert(captor.capture());
        return captor.getValue();
    }

    private static AlarmRule rule(Long id, String level, String threshold, String recovery) {
        AlarmRule r = new AlarmRule();
        r.setId(id);
        r.setName("规则" + id);
        r.setMetricCode(METRIC);
        r.setRuleType("THRESHOLD");
        r.setOperator("gte");
        r.setAlarmLevel(level);
        r.setThresholdValue(new BigDecimal(threshold));
        r.setRecoveryValue(recovery == null ? null : new BigDecimal(recovery));
        r.setEnabled(true);
        return r;
    }

    private static Alarm openAlarm(Long id, Long ruleId, String level, String status) {
        Alarm a = new Alarm();
        a.setId(id);
        a.setAlarmType(AlarmConstants.TYPE_POINT);
        a.setPointId(POINT_ID);
        a.setRuleId(ruleId);
        a.setMetricCode(METRIC);
        a.setOpenKey("P:" + POINT_ID + ":" + METRIC);
        a.setAlarmLevel(level);
        a.setStatus(status);
        a.setTriggeredAt(LocalDateTime.now().minusMinutes(1));
        return a;
    }

}
