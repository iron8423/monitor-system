-- =====================================================================
-- V11 雷达空间标定与山地数字孪生 V2
--
-- 1. 设备补齐天线高度与垂直视场；
-- 2. device_point 从“简单关系”升级为“已标定雷达目标”；
-- 3. 演示项目改为两台雷达，所有绑定均由同一地形生成器完成视线校验；
-- 4. 升级离线山体版本及校验值。
-- =====================================================================

ALTER TABLE device ADD COLUMN antenna_height_m NUMERIC(8, 3) DEFAULT 8.800;
ALTER TABLE device ADD COLUMN vertical_half_angle_degrees NUMERIC(9, 4) DEFAULT 15.0000;

ALTER TABLE device_point ADD COLUMN target_code VARCHAR(96);
ALTER TABLE device_point ADD COLUMN azimuth_degrees NUMERIC(9, 4);
ALTER TABLE device_point ADD COLUMN elevation_degrees NUMERIC(9, 4);
ALTER TABLE device_point ADD COLUMN slant_range_m NUMERIC(12, 3);
ALTER TABLE device_point ADD COLUMN reflector_height_m NUMERIC(8, 3);
ALTER TABLE device_point ADD COLUMN line_of_sight BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE device_point ADD COLUMN minimum_clearance_m NUMERIC(10, 3);
ALTER TABLE device_point ADD COLUMN calibration_status VARCHAR(16) NOT NULL DEFAULT 'PENDING';
ALTER TABLE device_point ADD COLUMN calibrated_at TIMESTAMP;
ALTER TABLE device_point ADD COLUMN valid_from TIMESTAMP;
ALTER TABLE device_point ADD COLUMN valid_to TIMESTAMP;
ALTER TABLE device_point ADD COLUMN calibration_note VARCHAR(512);

CREATE INDEX idx_device_point_calibration ON device_point (device_id, calibration_status, line_of_sight);

-- 精细版模型沿用相同 ENU 平面坐标，点位经纬度不变，只更新地形高程。
UPDATE monitor_point SET altitude = 60.785, updated_at = CURRENT_TIMESTAMP WHERE id = 1;
UPDATE monitor_point SET altitude = 57.137, updated_at = CURRENT_TIMESTAMP WHERE id = 2;
UPDATE monitor_point SET altitude = 46.096, updated_at = CURRENT_TIMESTAMP WHERE id = 3;
UPDATE monitor_point SET altitude = 42.885, updated_at = CURRENT_TIMESTAMP WHERE id = 4;
UPDATE monitor_point SET altitude = 51.492, updated_at = CURRENT_TIMESTAMP WHERE id = 5;
UPDATE monitor_point SET altitude = 39.955, updated_at = CURRENT_TIMESTAMP WHERE id = 6;
UPDATE monitor_point SET altitude = 40.592, updated_at = CURRENT_TIMESTAMP WHERE id = 7;

-- 北侧雷达：覆盖山脊与滑坡体上部。
UPDATE device
SET name = '北侧山脊形变雷达',
    serial_no = 'RADAR-2026-NORTH-001',
    longitude = 113.0517807,
    latitude = 23.7217029,
    altitude = 23.225,
    heading_degrees = 232.0,
    pitch_degrees = 10.0,
    detection_range_m = 265.0,
    half_angle_degrees = 30.0,
    antenna_height_m = 10.0,
    vertical_half_angle_degrees = 15.0,
    updated_at = CURRENT_TIMESTAMP
WHERE id = 1;

-- 南侧雷达：覆盖背坡可见区与滑坡体中下部。
INSERT INTO device (
    id, code, name, type, serial_no, longitude, latitude,
    altitude, heading_degrees, pitch_degrees, detection_range_m,
    half_angle_degrees, antenna_height_m, vertical_half_angle_degrees,
    status, battery, last_report_time, created_at, updated_at
) VALUES (
    2, 'radar-002', '南侧滑坡形变雷达', 'MILLIMETER_WAVE_RADAR', 'RADAR-2026-SOUTH-002',
    113.0514865, 23.7201680, 34.866, 314.0, 1.0, 265.0,
    30.0, 10.0, 15.0, 'ONLINE', 96.0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

-- 历史测量值保留，但从本版本起新的上报必须命中 ACTIVE + LOS 的标定关系。
DELETE FROM device_point WHERE device_id IN (1, 2);

INSERT INTO device_point (
    id, device_id, point_id, target_code,
    azimuth_degrees, elevation_degrees, slant_range_m, reflector_height_m,
    line_of_sight, minimum_clearance_m, calibration_status,
    calibrated_at, valid_from, calibration_note
) VALUES
(11, 1, 2, 'radar-001-P-HK02', 252.734,  9.546, 157.160, 2.500, TRUE, 2.695, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '程序化地形 V2 视线校验通过'),
(12, 1, 3, 'radar-001-P-HK03', 250.735,  7.839, 110.139, 2.500, TRUE, 1.671, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '程序化地形 V2 视线校验通过'),
(13, 1, 4, 'radar-001-P-BP01', 234.071,  7.892,  86.026, 2.500, TRUE, 1.235, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '程序化地形 V2 视线校验通过'),
(14, 1, 5, 'radar-001-P-BP02', 211.880, 11.939,  98.701, 2.500, TRUE, 0.315, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '程序化地形 V2 视线校验通过'),
(15, 2, 1, 'radar-002-P-HK01', 299.055,  5.569, 186.200, 2.500, TRUE, 1.409, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '程序化地形 V2 视线校验通过'),
(16, 2, 6, 'radar-002-P-BP03', 343.740, -3.160,  50.076, 2.500, TRUE, 2.551, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '程序化地形 V2 视线校验通过'),
(17, 2, 7, 'radar-002-P-BP04', 284.470, -3.795,  32.086, 2.500, TRUE, 2.516, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '程序化地形 V2 视线校验通过');

UPDATE digital_twin_scene
SET asset_version = 'mountain-demo-2.0.0',
    asset_sha256 = '0e2872fcd851b416b64b240e9bb20a84536a6cdd3c397198c7c3777b48ba5139',
    updated_at = CURRENT_TIMESTAMP
WHERE project_id = 1;
