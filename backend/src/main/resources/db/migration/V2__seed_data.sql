-- =====================================================================
-- V2 种子数据（静态部分；4 个演示账号由 DataInitializer 运行时创建，
--     因为密码需 bcrypt 编码）
-- 组织：清远电厂；项目：灰库/库区边坡形变监测；7 测点（灰库 3 + 边坡 4）
-- =====================================================================

-- 组织
INSERT INTO organization (id, name, code, contact, phone, created_at, updated_at)
VALUES (1, '清远电厂', 'ORG-QY', '王工', '0763-0000000', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 项目
INSERT INTO project (id, organization_id, name, code, location, description, created_at, updated_at)
VALUES (1, 1, '清远电厂灰库/库区边坡形变监测', 'PRJ-QY-HK', '广东省清远市清城区',
        '毫米波点形变雷达监测灰库体与库区边坡累计形变', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 场景
INSERT INTO scene (id, project_id, name, type, description, created_at, updated_at) VALUES
(1, 1, '灰库', 'ASH_SILO', '灰库体结构监测（3 个测点）', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(2, 1, '库区边坡', 'SLOPE', '库区边坡稳定性监测（4 个测点）', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 监测对象
INSERT INTO monitor_object (id, scene_id, name, type, description, created_at, updated_at) VALUES
(1, 1, '灰库体', 'SILO_BODY', '灰库主体', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(2, 2, '边坡体', 'SLOPE_BODY', '库区边坡', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 测点（灰库 3 点 + 边坡 4 点）
INSERT INTO monitor_point (id, object_id, code, name, type, longitude, latitude, altitude, enabled, created_at, updated_at) VALUES
(1, 1, 'P-HK01', '灰库测点1', 'POINT_DEFORMATION', 113.0100000, 23.7000000, 30.000, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(2, 1, 'P-HK02', '灰库测点2', 'POINT_DEFORMATION', 113.0115000, 23.7006000, 32.000, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(3, 1, 'P-HK03', '灰库测点3', 'POINT_DEFORMATION', 113.0108000, 23.7014000, 35.000, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(4, 2, 'P-BP01', '边坡测点1', 'POINT_DEFORMATION', 113.0500000, 23.7200000, 55.000, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(5, 2, 'P-BP02', '边坡测点2', 'POINT_DEFORMATION', 113.0512000, 23.7205000, 58.000, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(6, 2, 'P-BP03', '边坡测点3', 'POINT_DEFORMATION', 113.0506000, 23.7213000, 62.000, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(7, 2, 'P-BP04', '边坡测点4', 'POINT_DEFORMATION', 113.0521000, 23.7218000, 66.000, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 测项：每点 2 项（D1 定案 2026-09-09，对齐 docs/message-contract.md）
--   真实毫米波点形变雷达每点仅输出：defo_mm(累计形变) + rate_mm_d(速率，由历史推导)，无 X/Y/Z 三分量
INSERT INTO metric (id, point_id, code, name, unit, sort_order, created_at, updated_at) VALUES
(1,  1, 'defo_mm',   '累计形变', 'mm',   1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(2,  1, 'rate_mm_d', '形变速率', 'mm/d', 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(3,  2, 'defo_mm',   '累计形变', 'mm',   1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(4,  2, 'rate_mm_d', '形变速率', 'mm/d', 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(5,  3, 'defo_mm',   '累计形变', 'mm',   1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(6,  3, 'rate_mm_d', '形变速率', 'mm/d', 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(7,  4, 'defo_mm',   '累计形变', 'mm',   1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(8,  4, 'rate_mm_d', '形变速率', 'mm/d', 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(9,  5, 'defo_mm',   '累计形变', 'mm',   1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(10, 5, 'rate_mm_d', '形变速率', 'mm/d', 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(11, 6, 'defo_mm',   '累计形变', 'mm',   1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(12, 6, 'rate_mm_d', '形变速率', 'mm/d', 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(13, 7, 'defo_mm',   '累计形变', 'mm',   1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(14, 7, 'rate_mm_d', '形变速率', 'mm/d', 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 设备：1 台毫米波雷达
INSERT INTO device (id, code, name, type, serial_no, longitude, latitude, status, battery, last_report_time, created_at, updated_at)
VALUES (1, 'radar-001', '毫米波点形变雷达 1 号', 'MILLIMETER_WAVE_RADAR', 'RADAR-2026-001',
        113.0200000, 23.7050000, 'ONLINE', 92.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 设备-测点绑定（1 台雷达绑定全部 7 个测点）
INSERT INTO device_point (id, device_id, point_id) VALUES
(1, 1, 1), (2, 1, 2), (3, 1, 3), (4, 1, 4), (5, 1, 5), (6, 1, 6), (7, 1, 7);

-- 默认告警规则：双向 |abs| ±3mm（A-3 决策 2026-09-09，全局规则 point_id=NULL）
-- 依据：真实 20260827 defo 范围约 -3.6 ~ +3.3mm，原 gte 10 几乎不触发；
--       B 的 --inject-overlimit 以 ~4mm 为参考注入，故阈值取 ±3.0（含恢复 ±1.0）
INSERT INTO alarm_rule (id, name, point_id, metric_code, rule_type, operator, threshold_value, window_minutes, recovery_value, alarm_level, repeat_suppress_seconds, enabled, created_at, updated_at)
VALUES (1, '形变正向超限阈值', NULL, 'defo_mm', 'THRESHOLD', 'gte',  3.000000, NULL,  1.000000, 'warning', 300, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
       (2, '形变负向超限阈值', NULL, 'defo_mm', 'THRESHOLD', 'lte', -3.000000, NULL, -1.000000, 'warning', 300, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
