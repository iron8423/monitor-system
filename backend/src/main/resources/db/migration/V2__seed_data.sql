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

-- 测项：每点 5 项（累计形变 X/Y/Z、合位移、速率）
INSERT INTO metric (id, point_id, code, name, unit, sort_order, created_at, updated_at) VALUES
(1,  1, 'X',    '累计形变X', 'mm',   1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(2,  1, 'Y',    '累计形变Y', 'mm',   2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(3,  1, 'Z',    '累计形变Z', 'mm',   3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(4,  1, 'DISP', '合位移',    'mm',   4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(5,  1, 'VEL',  '速率',      'mm/d', 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(6,  2, 'X',    '累计形变X', 'mm',   1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(7,  2, 'Y',    '累计形变Y', 'mm',   2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(8,  2, 'Z',    '累计形变Z', 'mm',   3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(9,  2, 'DISP', '合位移',    'mm',   4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(10, 2, 'VEL',  '速率',      'mm/d', 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(11, 3, 'X',    '累计形变X', 'mm',   1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(12, 3, 'Y',    '累计形变Y', 'mm',   2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(13, 3, 'Z',    '累计形变Z', 'mm',   3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(14, 3, 'DISP', '合位移',    'mm',   4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(15, 3, 'VEL',  '速率',      'mm/d', 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(16, 4, 'X',    '累计形变X', 'mm',   1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(17, 4, 'Y',    '累计形变Y', 'mm',   2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(18, 4, 'Z',    '累计形变Z', 'mm',   3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(19, 4, 'DISP', '合位移',    'mm',   4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(20, 4, 'VEL',  '速率',      'mm/d', 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(21, 5, 'X',    '累计形变X', 'mm',   1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(22, 5, 'Y',    '累计形变Y', 'mm',   2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(23, 5, 'Z',    '累计形变Z', 'mm',   3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(24, 5, 'DISP', '合位移',    'mm',   4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(25, 5, 'VEL',  '速率',      'mm/d', 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(26, 6, 'X',    '累计形变X', 'mm',   1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(27, 6, 'Y',    '累计形变Y', 'mm',   2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(28, 6, 'Z',    '累计形变Z', 'mm',   3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(29, 6, 'DISP', '合位移',    'mm',   4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(30, 6, 'VEL',  '速率',      'mm/d', 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(31, 7, 'X',    '累计形变X', 'mm',   1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(32, 7, 'Y',    '累计形变Y', 'mm',   2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(33, 7, 'Z',    '累计形变Z', 'mm',   3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(34, 7, 'DISP', '合位移',    'mm',   4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(35, 7, 'VEL',  '速率',      'mm/d', 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 设备：1 台毫米波雷达
INSERT INTO device (id, code, name, type, serial_no, longitude, latitude, status, battery, last_report_time, created_at, updated_at)
VALUES (1, 'RADAR-001', '毫米波点形变雷达 1 号', 'MILLIMETER_WAVE_RADAR', 'RADAR-2026-001',
        113.0200000, 23.7050000, 'ONLINE', 92.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 设备-测点绑定（1 台雷达绑定全部 7 个测点）
INSERT INTO device_point (id, device_id, point_id) VALUES
(1, 1, 1), (2, 1, 2), (3, 1, 3), (4, 1, 4), (5, 1, 5), (6, 1, 6), (7, 1, 7);

-- 默认告警规则：合位移(累计) >= 10mm 触发 warning，回落到 5mm 恢复（全局规则 point_id=NULL）
-- 注：metric_code 暂用现有测项 DISP，D1 冻结后与 defo_mm/rate_mm_d 对齐
INSERT INTO alarm_rule (id, name, point_id, metric_code, rule_type, operator, threshold_value, window_minutes, recovery_value, alarm_level, repeat_suppress_seconds, enabled, created_at, updated_at)
VALUES (1, '合位移累计阈值', NULL, 'DISP', 'THRESHOLD', 'gte', 10.000000, NULL, 5.000000, 'warning', 300, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
