-- =====================================================================
-- V3 警情表支持设备告警（验收第 5 条：断连 -> 设备标离线 + 生成设备告警）
--
-- 背景：V1 的 alarm.point_id 是 NOT NULL，设备告警没有测点可挂，「生成设备告警」
--       一直无处落地。这里不另起 device_alarm 表，而是给 alarm 加一个来源维度——
--       设备告警直接复用现成的状态机（PENDING->CONFIRMED->...->RESOLVED）、
--       处置动作、时间线留痕与 SSE 推送，不必把警情域复制第二份。
-- A 唯一维护 schema（后续阶段清单 A-4）：M0 冻结后只进 V3，不回头改 V1/V2。
-- =====================================================================

-- 存量行一律是测点警情
ALTER TABLE alarm ADD COLUMN alarm_type VARCHAR(16) NOT NULL DEFAULT 'POINT';
ALTER TABLE alarm ADD COLUMN device_id  BIGINT;

-- 设备告警挂设备不挂测点
ALTER TABLE alarm ALTER COLUMN point_id DROP NOT NULL;

CREATE INDEX idx_alarm_device ON alarm (device_id, triggered_at);
