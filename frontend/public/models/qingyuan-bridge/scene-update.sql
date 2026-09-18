-- 由 tools/terrain_asset/build_terrain_asset.py 生成，请人工评审后再作为迁移使用。
-- 资产版本：qingyuan-bridge-1.0.0
-- GLB SHA-256：ea1600647e4d54dedfd1a752485e0cde34b160ffff5946e673dbc888631f3ad6

UPDATE monitor_point SET longitude = 113.0741950, latitude = 23.7956000, altitude = 326.550, updated_at = CURRENT_TIMESTAMP WHERE id = 10;
UPDATE monitor_point SET longitude = 113.0747150, latitude = 23.7956000, altitude = 326.550, updated_at = CURRENT_TIMESTAMP WHERE id = 11;
UPDATE monitor_point SET longitude = 113.0752351, latitude = 23.7956000, altitude = 326.550, updated_at = CURRENT_TIMESTAMP WHERE id = 12;
UPDATE monitor_point SET longitude = 113.0757649, latitude = 23.7956000, altitude = 326.550, updated_at = CURRENT_TIMESTAMP WHERE id = 13;
UPDATE monitor_point SET longitude = 113.0762850, latitude = 23.7956000, altitude = 326.550, updated_at = CURRENT_TIMESTAMP WHERE id = 14;
UPDATE monitor_point SET longitude = 113.0768050, latitude = 23.7956000, altitude = 326.550, updated_at = CURRENT_TIMESTAMP WHERE id = 15;

UPDATE device SET longitude = 113.0771190, latitude = 23.7965932, altitude = 304.062, heading_degrees = 222.980, pitch_degrees = 4.236, detection_range_m = 420.000, half_angle_degrees = 30.000, vertical_half_angle_degrees = 15.000, antenna_height_m = 10.000, updated_at = CURRENT_TIMESTAMP WHERE id = 3;
DELETE FROM device_point WHERE device_id IN (3);

INSERT INTO device_point (
    id, device_id, point_id, target_code,
    azimuth_degrees, elevation_degrees, slant_range_m, reflector_height_m,
    line_of_sight, minimum_clearance_m, calibration_status,
    calibrated_at, valid_from, calibration_note
) VALUES
(11, 3, 10, 'radar-bridge-01-P-BR01', 249.740, 2.701, 318.007, 2.500, TRUE, 5.789, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产视线校验通过'),
(12, 3, 11, 'radar-bridge-01-P-BR02', 245.821, 3.194, 268.979, 2.500, TRUE, 10.532, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产视线校验通过'),
(13, 3, 12, 'radar-bridge-01-P-BR03', 240.191, 3.875, 221.785, 2.500, TRUE, 10.528, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产视线校验通过'),
(14, 3, 13, 'radar-bridge-01-P-BR04', 231.442, 4.854, 177.112, 2.500, TRUE, 10.512, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产视线校验通过'),
(15, 3, 14, 'radar-bridge-01-P-BR05', 217.694, 6.154, 139.820, 2.500, TRUE, 10.462, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产视线校验通过'),
(16, 3, 15, 'radar-bridge-01-P-BR06', 196.220, 7.454, 115.536, 2.500, TRUE, 10.334, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产视线校验通过');

UPDATE digital_twin_scene SET asset_url = '/models/qingyuan-bridge/qingyuan-bridge.glb', asset_version = 'qingyuan-bridge-1.0.0', asset_sha256 = 'ea1600647e4d54dedfd1a752485e0cde34b160ffff5946e673dbc888631f3ad6', anchor_longitude = 113.0755000, anchor_latitude = 23.7956000, anchor_height = 243.000, camera_heading_degrees = 300.000, camera_pitch_degrees = -26.000, camera_range = 620.000, updated_at = CURRENT_TIMESTAMP WHERE project_id = 1;
