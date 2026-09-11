# 给 A 的待办 · V2 种子按冻结口径对齐（2 处）

> 日期：2026-09-09 | 提交人：B | 依据：M0 冻结（D1：每点 2 测项；D6：默认规则 ±3 双向）
> 范围：只改 `backend/src/main/resources/db/migration/V2__seed_data.sql`。下方 B1 实体对齐由 B 自己处理，不占 A 的活。

---

## 1. 种子测项：每点 5 项 → 改为每点 2 项

现在 `V2__seed_data.sql` 里每个 `monitor_point` 插了 5 条 `metric`：
`X`(mm)、`Y`(mm)、`Z`(mm)、`DISP`(mm)、`VEL`(mm/d)。

**按冻结 D1（点形变雷达只有累计形变 + 速率，无 X/Y/Z）改为每点 2 项：**

| metricCode | name | unit |
|---|---|---|
| `defo_mm` | 累计形变 | mm |
| `rate_mm_d` | 形变速率 | mm/d |

即：删掉 `X/Y/Z/DISP/VEL` 那 5 条，换成上面 2 条，每个点都如此（共 7 点 × 2 条 = 14 条 `metric`，而非现在 35 条）。对应 `metric.id` 从 1 起每点 +2。

> 你的 V2 注释里已经写了“D1 冻结后与 defo_mm/rate_mm_d 对齐”，但数据还没改，麻烦落地。

## 2. 默认告警规则：`DISP ≥10` 全局 → 改为 `defo_mm ±3` 双向

现在默认规则：
```sql
INSERT INTO alarm_rule (..., metric_code, rule_type, operator, threshold_value, ...)
VALUES (1, '合位移累计阈值', NULL, 'DISP', 'THRESHOLD', 'gte', 10, ..., 5, 'warning', 300, TRUE, ...);
```

**按冻结 D6 改为两条（都作用于 defo_mm，level=warning）：**

```sql
-- 规则①：正向 ≥ +3.0 触发，回落到 +1.0 恢复
INSERT INTO alarm_rule (id, name, point_id, metric_code, rule_type, operator, threshold_value, window_minutes, recovery_value, alarm_level, repeat_suppress_seconds, enabled, created_at, updated_at)
VALUES (1, '累计形变正向阈值', NULL, 'defo_mm', 'THRESHOLD', 'gte', 3.0, NULL, 1.0, 'warning', 300, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 规则②：负向 ≤ -3.0 触发，回落到 -1.0 恢复
INSERT INTO alarm_rule (id, name, point_id, metric_code, rule_type, operator, threshold_value, window_minutes, recovery_value, alarm_level, repeat_suppress_seconds, enabled, created_at, updated_at)
VALUES (2, '累计形变负向阈值', NULL, 'defo_mm', 'THRESHOLD', 'lte', -3.0, NULL, -1.0, 'warning', 300, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
```

> 原因：真实点形变雷达 20260827 累计形变约 **-3.6 ~ +3.3 mm**，`DISP≥10` 永远触发不了；±3 才符合数据量级。

---

改完这两个点，`V2__seed_data.sql` 即与 M0 冻结完全一致。B 侧 B1（`Measurement`/`IngestService` 对齐 `measurement` 表的 `point_id/measure_value/seq/unit`）由 B 自己改，无需 A。
