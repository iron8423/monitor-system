-- 由 tools/terrain_asset/build_terrain_asset.py 生成，请人工评审后再作为迁移使用。
-- 资产版本：qingyuan-railway-1.0.0
-- GLB SHA-256：c72e0bcf83012e3085f88966508f2183c7d1a55526e8443fa84c57ece7ee3862

UPDATE monitor_point SET longitude = 113.0196935, latitude = 23.7204594, altitude = 10.859, updated_at = CURRENT_TIMESTAMP WHERE id = 16;
UPDATE monitor_point SET longitude = 113.0205761, latitude = 23.7204594, altitude = 13.443, updated_at = CURRENT_TIMESTAMP WHERE id = 17;
UPDATE monitor_point SET longitude = 113.0214587, latitude = 23.7204594, altitude = 16.028, updated_at = CURRENT_TIMESTAMP WHERE id = 18;
UPDATE monitor_point SET longitude = 113.0223413, latitude = 23.7204594, altitude = 18.612, updated_at = CURRENT_TIMESTAMP WHERE id = 19;
UPDATE monitor_point SET longitude = 113.0232239, latitude = 23.7204594, altitude = 21.197, updated_at = CURRENT_TIMESTAMP WHERE id = 20;
UPDATE monitor_point SET longitude = 113.0241065, latitude = 23.7204594, altitude = 23.782, updated_at = CURRENT_TIMESTAMP WHERE id = 21;

UPDATE device SET longitude = 113.0245478, latitude = 23.7220349, altitude = 27.572, heading_degrees = 222.521, pitch_degrees = -3.645, detection_range_m = 620.000, half_angle_degrees = 30.000, vertical_half_angle_degrees = 15.000, antenna_height_m = 10.000, updated_at = CURRENT_TIMESTAMP WHERE id = 4;
DELETE FROM device_point WHERE device_id IN (4);

INSERT INTO device_point (
    id, device_id, point_id, target_code,
    azimuth_degrees, elevation_degrees, slant_range_m, reflector_height_m,
    line_of_sight, minimum_clearance_m, calibration_status,
    calibrated_at, valid_from, calibration_note
) VALUES
(11, 4, 16, 'radar-rail-01-P-RW01', 250.581, -2.641, 525.416, 2.500, TRUE, 1.582, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产视线校验通过'),
(12, 4, 17, 'radar-rail-01-P-RW02', 246.691, -2.808, 441.524, 2.500, TRUE, 3.274, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产视线校验通过'),
(13, 4, 18, 'radar-rail-01-P-RW03', 241.015, -3.027, 360.608, 2.500, TRUE, 3.585, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产视线校验通过'),
(14, 4, 19, 'radar-rail-01-P-RW04', 232.204, -3.308, 285.212, 2.500, TRUE, 4.119, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产视线校验通过'),
(15, 4, 20, 'radar-rail-01-P-RW05', 217.727, -3.599, 221.061, 2.500, TRUE, 1.813, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产视线校验通过'),
(16, 4, 21, 'radar-rail-01-P-RW06', 194.460, -3.585, 180.562, 2.500, TRUE, 1.272, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产视线校验通过');

UPDATE digital_twin_scene SET asset_url = '/models/qingyuan-railway/qingyuan-railway.glb', asset_version = 'qingyuan-railway-1.0.0', asset_sha256 = 'c72e0bcf83012e3085f88966508f2183c7d1a55526e8443fa84c57ece7ee3862', anchor_longitude = 113.0219000, anchor_latitude = 23.7205000, anchor_height = 8.000, camera_heading_degrees = 300.000, camera_pitch_degrees = -24.000, camera_range = 780.000, updated_at = CURRENT_TIMESTAMP WHERE project_id = 1;
