-- 由 tools/terrain_asset/build_terrain_asset.py 生成，请人工评审后再作为迁移使用。
-- 资产版本：qingyuan-factory-1.0.0
-- GLB SHA-256：dda1e8ad8515209a58a7ce6919aa3148bf254bb3ad1d1c7b2ed3bc24105ff1d9

UPDATE monitor_point SET longitude = 113.0773137, latitude = 23.6944334, altitude = 19.124, updated_at = CURRENT_TIMESTAMP WHERE id = 22;
UPDATE monitor_point SET longitude = 113.0777059, latitude = 23.6942709, altitude = 19.124, updated_at = CURRENT_TIMESTAMP WHERE id = 23;
UPDATE monitor_point SET longitude = 113.0781961, latitude = 23.6935485, altitude = 19.124, updated_at = CURRENT_TIMESTAMP WHERE id = 24;
UPDATE monitor_point SET longitude = 113.0774117, latitude = 23.6936388, altitude = 19.124, updated_at = CURRENT_TIMESTAMP WHERE id = 25;
UPDATE monitor_point SET longitude = 113.0786863, latitude = 23.6942528, altitude = 19.124, updated_at = CURRENT_TIMESTAMP WHERE id = 26;
UPDATE monitor_point SET longitude = 113.0789315, latitude = 23.6940451, altitude = 19.124, updated_at = CURRENT_TIMESTAMP WHERE id = 27;

UPDATE device SET longitude = 113.0800590, latitude = 23.6953544, altitude = 17.833, heading_degrees = 234.201, pitch_degrees = -2.103, detection_range_m = 520.000, half_angle_degrees = 30.000, vertical_half_angle_degrees = 15.000, antenna_height_m = 10.000, updated_at = CURRENT_TIMESTAMP WHERE id = 5;
DELETE FROM device_point WHERE device_id IN (5);

INSERT INTO device_point (
    id, device_id, point_id, target_code,
    azimuth_degrees, elevation_degrees, slant_range_m, reflector_height_m,
    line_of_sight, minimum_clearance_m, calibration_status,
    calibrated_at, valid_from, calibration_note
) VALUES
(11, 5, 22, 'radar-factory-01-P-FC01', 249.984, -1.194, 298.065, 2.500, TRUE, 4.202, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产视线校验通过'),
(12, 5, 23, 'radar-factory-01-P-FC02', 243.435, -1.326, 268.400, 2.500, TRUE, 4.612, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产视线校验通过'),
(13, 5, 24, 'radar-factory-01-P-FC03', 223.531, -1.289, 275.932, 2.500, TRUE, 4.961, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产视线校验通过'),
(14, 5, 25, 'radar-factory-01-P-FC04', 234.866, -1.077, 330.210, 2.500, TRUE, 5.757, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产视线校验通过'),
(15, 5, 26, 'radar-factory-01-P-FC05', 228.930, -1.915, 185.802, 2.500, TRUE, 4.224, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产视线校验通过'),
(16, 5, 27, 'radar-factory-01-P-FC06', 218.418, -1.921, 185.172, 2.500, TRUE, 4.751, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产视线校验通过');

UPDATE digital_twin_scene SET asset_url = '/models/qingyuan-factory/qingyuan-factory.glb', asset_version = 'qingyuan-factory-1.0.0', asset_sha256 = 'dda1e8ad8515209a58a7ce6919aa3148bf254bb3ad1d1c7b2ed3bc24105ff1d9', anchor_longitude = 113.0780000, anchor_latitude = 23.6940000, anchor_height = 10.000, camera_heading_degrees = 315.000, camera_pitch_degrees = -32.000, camera_range = 700.000, updated_at = CURRENT_TIMESTAMP WHERE project_id = 1;
