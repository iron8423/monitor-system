-- 由 tools/terrain_asset/build_terrain_asset.py 生成，请人工评审后再作为迁移使用。
-- 资产版本：qingyuan-hillside-2.0.0
-- GLB SHA-256：d0087127c07f1daecbbbe938f7de0ace029b58d36807d6ce2969da8c1c9f4b72

UPDATE monitor_point SET longitude = 113.0498585, latitude = 23.7612658, altitude = 211.734, updated_at = CURRENT_TIMESTAMP WHERE id = 1;
UPDATE monitor_point SET longitude = 113.0503490, latitude = 23.7603629, altitude = 198.757, updated_at = CURRENT_TIMESTAMP WHERE id = 2;
UPDATE monitor_point SET longitude = 113.0513300, latitude = 23.7599114, altitude = 180.660, updated_at = CURRENT_TIMESTAMP WHERE id = 3;
UPDATE monitor_point SET longitude = 113.0518205, latitude = 23.7599114, altitude = 171.009, updated_at = CURRENT_TIMESTAMP WHERE id = 4;
UPDATE monitor_point SET longitude = 113.0518205, latitude = 23.7590086, altitude = 141.753, updated_at = CURRENT_TIMESTAMP WHERE id = 5;
UPDATE monitor_point SET longitude = 113.0518205, latitude = 23.7581057, altitude = 107.385, updated_at = CURRENT_TIMESTAMP WHERE id = 6;
UPDATE monitor_point SET longitude = 113.0532919, latitude = 23.7581057, altitude = 107.046, updated_at = CURRENT_TIMESTAMP WHERE id = 7;

UPDATE device SET longitude = 113.0528015, latitude = 23.7621687, altitude = 131.001, heading_degrees = 226.683, pitch_degrees = 9.484, detection_range_m = 620.000, half_angle_degrees = 30.000, vertical_half_angle_degrees = 15.000, antenna_height_m = 10.000, updated_at = CURRENT_TIMESTAMP WHERE id = 1;
UPDATE device SET longitude = 113.0523110, latitude = 23.7567513, altitude = 71.275, heading_degrees = 7.628, pitch_degrees = 10.740, detection_range_m = 620.000, half_angle_degrees = 30.000, vertical_half_angle_degrees = 15.000, antenna_height_m = 10.000, updated_at = CURRENT_TIMESTAMP WHERE id = 2;
DELETE FROM device_point WHERE device_id IN (1, 2);

INSERT INTO device_point (
    id, device_id, point_id, target_code,
    azimuth_degrees, elevation_degrees, slant_range_m, reflector_height_m,
    line_of_sight, minimum_clearance_m, calibration_status,
    calibrated_at, valid_from, calibration_note
) VALUES
(11, 1, 1, 'radar-001-P-HK01', 251.565, 13.039, 324.597, 2.500, TRUE, 1.438, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产视线校验通过'),
(12, 1, 2, 'radar-001-P-HK02', 231.340, 10.659, 325.777, 2.500, TRUE, 1.150, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产视线校验通过'),
(13, 1, 3, 'radar-001-P-HK03', 210.964, 8.228, 294.580, 2.500, TRUE, 3.084, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产视线校验通过'),
(14, 1, 4, 'radar-001-P-BP01', 201.801, 6.884, 271.213, 2.500, TRUE, 2.883, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产视线校验通过'),
(15, 2, 5, 'radar-002-P-BP02', 348.690, 13.875, 262.614, 2.500, TRUE, 2.869, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产视线校验通过'),
(16, 2, 6, 'radar-002-P-BP03', 341.565, 10.256, 160.681, 2.500, TRUE, 2.839, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产视线校验通过'),
(17, 2, 7, 'radar-002-P-BP04', 33.690, 8.912, 182.481, 2.500, TRUE, 1.577, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产视线校验通过');

UPDATE digital_twin_scene SET asset_url = '/models/qingyuan-hillside-v2/qingyuan-hillside-v2.glb', asset_version = 'qingyuan-hillside-2.0.0', asset_sha256 = 'd0087127c07f1daecbbbe938f7de0ace029b58d36807d6ce2969da8c1c9f4b72', anchor_longitude = 113.0513300, anchor_latitude = 23.7594600, anchor_height = 43.000, camera_heading_degrees = 315.000, camera_pitch_degrees = -30.000, camera_range = 1650.000, updated_at = CURRENT_TIMESTAMP WHERE project_id = 1;
