-- 由 tools/terrain_asset/build_terrain_asset.py 生成，请人工评审后再作为迁移使用。
-- 资产版本：qingyuan-hillside-1.0.0
-- GLB SHA-256：01dc957935783fc3816487d47bc47896b8170d8058ede5129e998163d1bedbcd

UPDATE monitor_point SET longitude = 113.0502509, latitude = 23.7603178, altitude = 199.330, updated_at = CURRENT_TIMESTAMP WHERE id = 1;
UPDATE monitor_point SET longitude = 113.0508886, latitude = 23.7602726, altitude = 175.551, updated_at = CURRENT_TIMESTAMP WHERE id = 2;
UPDATE monitor_point SET longitude = 113.0513300, latitude = 23.7600017, altitude = 176.410, updated_at = CURRENT_TIMESTAMP WHERE id = 3;
UPDATE monitor_point SET longitude = 113.0518205, latitude = 23.7601823, altitude = 164.767, updated_at = CURRENT_TIMESTAMP WHERE id = 4;
UPDATE monitor_point SET longitude = 113.0517224, latitude = 23.7592794, altitude = 154.724, updated_at = CURRENT_TIMESTAMP WHERE id = 5;
UPDATE monitor_point SET longitude = 113.0520167, latitude = 23.7592794, altitude = 145.185, updated_at = CURRENT_TIMESTAMP WHERE id = 6;
UPDATE monitor_point SET longitude = 113.0523110, latitude = 23.7592794, altitude = 138.725, updated_at = CURRENT_TIMESTAMP WHERE id = 7;

UPDATE device SET longitude = 113.0523110, latitude = 23.7603629, altitude = 249.563, heading_degrees = 257.771, pitch_degrees = 11.698, detection_range_m = 265.000, half_angle_degrees = 30.000, vertical_half_angle_degrees = 15.000, antenna_height_m = 10.000, updated_at = CURRENT_TIMESTAMP WHERE id = 1;
UPDATE device SET longitude = 113.0527034, latitude = 23.7585571, altitude = 238.109, heading_degrees = 320.291, pitch_degrees = 2.246, detection_range_m = 265.000, half_angle_degrees = 30.000, vertical_half_angle_degrees = 15.000, antenna_height_m = 10.000, updated_at = CURRENT_TIMESTAMP WHERE id = 2;
DELETE FROM device_point WHERE device_id IN (1, 2);

INSERT INTO device_point (
    id, device_id, point_id, target_code,
    azimuth_degrees, elevation_degrees, slant_range_m, reflector_height_m,
    line_of_sight, minimum_clearance_m, calibration_status,
    calibrated_at, valid_from, calibration_note
) VALUES
(11, 1, 1, 'radar-001-P-HK01', 268.636, 13.200, 215.760, 2.500, TRUE, 1.924, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产视线校验通过'),
(12, 1, 2, 'radar-001-P-HK02', 266.055, 9.946, 147.562, 2.500, TRUE, 3.122, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产视线校验通过'),
(13, 1, 3, 'radar-001-P-HK03', 248.199, 13.746, 110.879, 2.500, TRUE, 0.778, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产视线校验通过'),
(14, 1, 4, 'radar-001-P-BP01', 248.199, 15.272, 55.823, 2.500, TRUE, 1.659, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产视线校验通过'),
(15, 2, 5, 'radar-002-P-BP02', 308.660, 7.172, 129.072, 2.500, TRUE, 3.249, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产视线校验通过'),
(16, 2, 6, 'radar-002-P-BP03', 318.814, 3.540, 106.505, 2.500, TRUE, 3.409, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产视线校验通过'),
(17, 2, 7, 'radar-002-P-BP04', 333.435, 0.074, 89.443, 2.500, TRUE, 1.284, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产视线校验通过');

UPDATE digital_twin_scene SET asset_url = '/models/qingyuan-hillside/qingyuan-hillside.glb', asset_version = 'qingyuan-hillside-1.0.0', asset_sha256 = '01dc957935783fc3816487d47bc47896b8170d8058ede5129e998163d1bedbcd', anchor_longitude = 113.0513300, anchor_latitude = 23.7594600, anchor_height = 107.000, camera_heading_degrees = 327.000, camera_pitch_degrees = -34.000, camera_range = 520.000, updated_at = CURRENT_TIMESTAMP WHERE project_id = 1;
