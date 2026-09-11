-- =====================================================================
-- V4 补一条更高等级的形变阈值规则
--
-- 验收第 3 条要求「等级升高能升级」，但 V2 只种了 gte +3.0 / lte -3.0 两条 **warning** 规则——
-- **没有更高等级可升**。不补这条，升级逻辑写没写都演示不出来，assertion 也无从下手。
--
-- 语义：defo 越过 +3.0 是 warning（趋势异常）；继续恶化到 +5.0 升为 alarm（险情）。
-- 恢复值取 +2.0：只有当它**自己**开出警情（值一步跳到 >= 5.0，没经过 warning 那条）时才用得上；
-- 若警情是先由 +3.0 规则开出、再升级上来的，恢复仍按最初那条规则的 +1.0 判定
-- （见 AlarmEngine 的等级升级说明——升级只改等级，不改所属规则）。
--
-- 负向未配对称的 -5.0：升级演示一条正向足够，且少一条全局规则就少一处跨套件干扰；
-- 需要时按同一形状加即可（id 续 4）。
-- =====================================================================

INSERT INTO alarm_rule (id, name, point_id, metric_code, rule_type, operator, threshold_value,
                        window_minutes, recovery_value, alarm_level, repeat_suppress_seconds,
                        enabled, created_at, updated_at)
VALUES (3, '形变正向告警阈值', NULL, 'defo_mm', 'THRESHOLD', 'gte', 5.000000,
        NULL, 2.000000, 'alarm', 300, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
