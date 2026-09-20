-- =====================================================================
-- PostgreSQL：把 measurement 改造成按月分区（复查清单 P1-1 的演进方案）
--
-- **这份脚本不在 Flyway 里，也不会被 `docker compose up` 自动执行。**
-- 原因见 docs/容量与保留策略_20260920.md §5：
--   ① PARTITION BY RANGE 是 PG 特性，H2（本地 + 全部验收套件）没有；
--   ② 存量改造要"建新表 → 拷数据 → 改名 → 重建索引"，在亿级表上是一次停机窗口。
--
-- 用法（维护窗口里手工执行，先备份）：
--   psql -U monitor -d monitor -v ON_ERROR_STOP=1 -f partition_measurement_pg.sql
-- 脚本末尾的 \echo 会告诉你还剩哪几步要人做（改名与索引重建刻意不自动化）。
--
-- ⚠️ **一个必须先读懂的语义变化**：PG 的声明式分区要求唯一索引包含分区键。
-- 本表的幂等唯一键是 (device_id, message_id, metric_code)，不含 collect_time，
-- 分区后**无法保持为全局唯一**——脚本给出的是"分区内唯一"，即把 collect_time 加进键。
-- 后果：同一 messageId 被伪造成跨月的时间戳时，理论上能同时存在两行。
-- 如果你的场景把"重发绝不重复入库"当硬约束，先解决幂等键设计（见文档 §5.2），
-- 不要直接接受这个降级。
--
-- 行数阈值参考：> 2 亿行 或 > 200 GB，或保留任务长期触到单轮上限。
-- =====================================================================

\set ON_ERROR_STOP on

-- ---------- 0. 前置检查（不满足就别继续） ----------
\echo '== 前置检查 =='
SELECT count(*) AS measurement_rows FROM measurement;
SELECT pg_size_pretty(pg_total_relation_size('measurement')) AS total_size;
-- 分区键上的 NULL 会被拒（PG 的 RANGE 分区对 NULL 报错）；本表的 collect_time 允许 NULL，
-- 分区前必须先把它们清掉或补上——这里只报告数量，处理方式由人决定。
SELECT count(*) AS rows_with_null_collect_time FROM measurement WHERE collect_time IS NULL;

\echo '若上面第二行(rows_with_null_collect_time)不为 0，请先处理：分区表的 RANGE 分区不接受 NULL 分区键。'

-- ---------- 1. 建分区父表（结构对齐 V1 + 后续迁移加过的列） ----------
\echo '== 建父表 measurement_partitioned =='
CREATE TABLE IF NOT EXISTS measurement_partitioned (
    id             BIGINT,
    point_id       BIGINT NOT NULL,
    point_code     VARCHAR(64),
    metric_id      BIGINT,
    metric_code    VARCHAR(32),
    device_id      VARCHAR(64),
    message_id     VARCHAR(64) NOT NULL,
    seq            BIGINT,
    schema_version VARCHAR(16),
    measure_value  NUMERIC(18, 6),
    unit           VARCHAR(16),
    quality        VARCHAR(16),
    attributes     VARCHAR(1024),
    raw_ref        VARCHAR(512),
    collect_time   TIMESTAMP NOT NULL,          -- 分区键：NOT NULL 是硬要求
    receive_time   TIMESTAMP,
    created_at     TIMESTAMP,
    ingest_mode    VARCHAR(16) DEFAULT 'REALTIME'
) PARTITION BY RANGE (collect_time);

-- 分区内唯一：**不再是全局唯一**，见文件头说明
CREATE UNIQUE INDEX IF NOT EXISTS uk_measurement_partitioned_msg
    ON measurement_partitioned (device_id, message_id, metric_code, collect_time);
CREATE INDEX IF NOT EXISTS idx_measurement_partitioned_point_time
    ON measurement_partitioned (point_id, collect_time);

-- ---------- 2. 建分区（示例：2026-08 ~ 2026-12 + 兜底分区） ----------
-- 生产上应当提前 3~6 个月建好后续月份；PG 11+ 允许先建 DEFAULT 分区兜底，
-- 但 DEFAULT 分区里的行不会再被自动搬走，长期看要点：**按月提前建**。
\echo '== 建月度分区 =='
DO $$
DECLARE
    m date;
    part_name text;
BEGIN
    FOR m IN SELECT generate_series(date_trunc('month', current_date) - interval '6 months',
                                    date_trunc('month', current_date) + interval '6 months',
                                    interval '1 month')::date
    LOOP
        part_name := 'measurement_' || to_char(m, 'YYYYMM');
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF measurement_partitioned FOR VALUES FROM (%L) TO (%L)',
            part_name, m, (m + interval '1 month')::date);
    END LOOP;
END $$;

-- 兜底分区：任何落在已建范围之外的行都会进这里（不丢数据，但这张表会越来越大，要盯着）
CREATE TABLE IF NOT EXISTS measurement_default
    PARTITION OF measurement_partitioned DEFAULT;

-- ---------- 3. 迁移存量数据 ----------
-- 大表上这一步最慢。按时间分块 INSERT 可以降低单事务体量（需要的话把下面拆成多条执行）。
\echo '== 迁移存量（大表请分块执行） =='
INSERT INTO measurement_partitioned (
    id, point_id, point_code, metric_id, metric_code, device_id, message_id, seq,
    schema_version, measure_value, unit, quality, attributes, raw_ref,
    collect_time, receive_time, created_at, ingest_mode)
SELECT
    id, point_id, point_code, metric_id, metric_code, device_id, message_id, seq,
    schema_version, measure_value, unit, quality, attributes, raw_ref,
    collect_time, receive_time, created_at, COALESCE(ingest_mode, 'REALTIME')
FROM measurement
WHERE collect_time IS NOT NULL;

SELECT count(*) AS migrated_rows FROM measurement_partitioned;

-- ---------- 4. 剩下的几步刻意留给人工 ----------
\echo ''
\echo '== 下面三步请人工确认后手工执行（脚本不自动改名，避免半途失败留下不一致） =='
\echo '  ① 校验行数一致：SELECT count(*) FROM measurement;  -- 与上面 migrated_rows 比对'
\echo '  ② 切换：      ALTER TABLE measurement RENAME TO measurement_old;'
\echo '                ALTER TABLE measurement_partitioned RENAME TO measurement;'
\echo '  ③ 观察若干天后再删旧表：DROP TABLE measurement_old;  -- 删除前务必有一次可用的备份'
\echo ''
\echo '注意：切换之后，应用侧的 INSERT 会走分区路由；'
\echo '      "同一 (device_id, message_id, metric_code) 只落一行"这条保证变成**分区内**唯一，'
\echo '      见 docs/容量与保留策略_20260920.md §5.2。'
