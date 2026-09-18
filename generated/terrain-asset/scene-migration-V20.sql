-- ---------- 野外桥梁（跨越谷地）（project 3） ----------
INSERT INTO project (id, organization_id, name, code, location, description, created_at, updated_at) VALUES (3, 1, '野外桥梁形变监测', 'PRJ-BRIDGE', '清远市郊 县道跨谷桥', '离线真实地形 + 程序化桥梁结构体；形变数据为模拟', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO scene (id, project_id, name, type, description, created_at, updated_at) VALUES (4, 3, '野外桥梁形变监测场景', 'BRIDGE', '野外桥梁（跨越谷地）', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO monitor_object (id, scene_id, name, type, description, created_at, updated_at) VALUES (4, 4, '野外桥梁（跨越谷地）', 'BRIDGE_DECK', '程序化结构体 + 真实地形', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO monitor_point (id, object_id, code, name, type, longitude, latitude, altitude, enabled, created_at, updated_at) VALUES
(9, 4, 'P-BR01', '桥梁测点1', 'DEFORMATION', 113.0741950, 23.7956000, 326.550, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(10, 4, 'P-BR02', '桥梁测点2', 'DEFORMATION', 113.0747150, 23.7956000, 326.550, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(11, 4, 'P-BR03', '桥梁测点3', 'DEFORMATION', 113.0752351, 23.7956000, 326.550, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(12, 4, 'P-BR04', '桥梁测点4', 'DEFORMATION', 113.0757649, 23.7956000, 326.550, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(13, 4, 'P-BR05', '桥梁测点5', 'DEFORMATION', 113.0762850, 23.7956000, 326.550, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(14, 4, 'P-BR06', '桥梁测点6', 'DEFORMATION', 113.0768050, 23.7956000, 326.550, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO metric (id, point_id, code, name, unit, sort_order, created_at, updated_at) VALUES
(100, 9, 'defo_mm', '累计形变', 'mm', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(101, 9, 'rate_mm_d', '形变速率', 'mm/d', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(102, 10, 'defo_mm', '累计形变', 'mm', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(103, 10, 'rate_mm_d', '形变速率', 'mm/d', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(104, 11, 'defo_mm', '累计形变', 'mm', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(105, 11, 'rate_mm_d', '形变速率', 'mm/d', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(106, 12, 'defo_mm', '累计形变', 'mm', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(107, 12, 'rate_mm_d', '形变速率', 'mm/d', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(108, 13, 'defo_mm', '累计形变', 'mm', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(109, 13, 'rate_mm_d', '形变速率', 'mm/d', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(110, 14, 'defo_mm', '累计形变', 'mm', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(111, 14, 'rate_mm_d', '形变速率', 'mm/d', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO device (id, code, name, type, serial_no, longitude, latitude, altitude, heading_degrees, pitch_degrees, detection_range_m, half_angle_degrees, antenna_height_m, vertical_half_angle_degrees, status, battery, last_report_time, created_at, updated_at) VALUES (3, 'radar-bridge-01', '桥梁形变雷达', 'MILLIMETER_WAVE_RADAR', 'RADAR-2026-BRIDGE-001', 113.0771190, 23.7965932, 304.062, 222.980, 4.236, 420.000, 30.000, 10.000, 15.000, 'ONLINE', 100.0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO device_point (id, device_id, point_id, target_code, azimuth_degrees, elevation_degrees, slant_range_m, reflector_height_m, line_of_sight, minimum_clearance_m, calibration_status, calibrated_at, valid_from, calibration_note) VALUES
(18, 3, 9, 'radar-bridge-01-P-BR01', 249.740, 2.701, 318.007, 2.500, TRUE, 5.789, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '程序化结构体视线校验通过'),
(19, 3, 10, 'radar-bridge-01-P-BR02', 245.821, 3.194, 268.979, 2.500, TRUE, 10.532, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '程序化结构体视线校验通过'),
(20, 3, 11, 'radar-bridge-01-P-BR03', 240.191, 3.875, 221.785, 2.500, TRUE, 10.528, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '程序化结构体视线校验通过'),
(21, 3, 12, 'radar-bridge-01-P-BR04', 231.442, 4.854, 177.112, 2.500, TRUE, 10.512, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '程序化结构体视线校验通过'),
(22, 3, 13, 'radar-bridge-01-P-BR05', 217.694, 6.154, 139.820, 2.500, TRUE, 10.462, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '程序化结构体视线校验通过'),
(23, 3, 14, 'radar-bridge-01-P-BR06', 196.220, 7.454, 115.536, 2.500, TRUE, 10.334, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '程序化结构体视线校验通过');
INSERT INTO digital_twin_scene (id, project_id, enabled, asset_type, coordinate_mode, asset_url, asset_version, asset_sha256, anchor_longitude, anchor_latitude, anchor_height, heading_degrees, pitch_degrees, roll_degrees, model_scale, camera_heading_degrees, camera_pitch_degrees, camera_range, maximum_screen_error, maximum_memory_mb, max_heat_points, label_distance, created_at, updated_at) VALUES (2, 3, TRUE, 'GLB', 'ENU', '/models/qingyuan-bridge/qingyuan-bridge.glb', 'qingyuan-bridge-1.0.0', 'e5b67958f861c75c7f9a5e9743d8d39aa0146692325e86d3a1e8665916cebe89', 113.0755000, 23.7956000, 243.000, 0, 0, 0, 1, 300.000, -26.000, 620.000, 16, 512, 200, 2000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- ---------- 山区铁路（路基与轨道）（project 4） ----------
INSERT INTO project (id, organization_id, name, code, location, description, created_at, updated_at) VALUES (4, 1, '山区铁路路基形变监测', 'PRJ-RAILWAY', '清远市郊 既有线 K12+300', '离线真实地形 + 程序化铁路路基/轨道；形变数据为模拟', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO scene (id, project_id, name, type, description, created_at, updated_at) VALUES (5, 4, '山区铁路路基形变监测场景', 'RAILWAY', '山区铁路（路基与轨道）', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO monitor_object (id, scene_id, name, type, description, created_at, updated_at) VALUES (5, 5, '山区铁路（路基与轨道）', 'RAILWAY_EMBANKMENT', '程序化结构体 + 真实地形', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO monitor_point (id, object_id, code, name, type, longitude, latitude, altitude, enabled, created_at, updated_at) VALUES
(15, 5, 'P-RW01', '铁路测点1', 'DEFORMATION', 113.0196935, 23.7204594, 10.859, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(16, 5, 'P-RW02', '铁路测点2', 'DEFORMATION', 113.0205761, 23.7204594, 13.443, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(17, 5, 'P-RW03', '铁路测点3', 'DEFORMATION', 113.0214587, 23.7204594, 16.028, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(18, 5, 'P-RW04', '铁路测点4', 'DEFORMATION', 113.0223413, 23.7204594, 18.612, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(19, 5, 'P-RW05', '铁路测点5', 'DEFORMATION', 113.0232239, 23.7204594, 21.197, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(20, 5, 'P-RW06', '铁路测点6', 'DEFORMATION', 113.0241065, 23.7204594, 23.782, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO metric (id, point_id, code, name, unit, sort_order, created_at, updated_at) VALUES
(112, 15, 'defo_mm', '累计形变', 'mm', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(113, 15, 'rate_mm_d', '形变速率', 'mm/d', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(114, 16, 'defo_mm', '累计形变', 'mm', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(115, 16, 'rate_mm_d', '形变速率', 'mm/d', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(116, 17, 'defo_mm', '累计形变', 'mm', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(117, 17, 'rate_mm_d', '形变速率', 'mm/d', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(118, 18, 'defo_mm', '累计形变', 'mm', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(119, 18, 'rate_mm_d', '形变速率', 'mm/d', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(120, 19, 'defo_mm', '累计形变', 'mm', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(121, 19, 'rate_mm_d', '形变速率', 'mm/d', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(122, 20, 'defo_mm', '累计形变', 'mm', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(123, 20, 'rate_mm_d', '形变速率', 'mm/d', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO device (id, code, name, type, serial_no, longitude, latitude, altitude, heading_degrees, pitch_degrees, detection_range_m, half_angle_degrees, antenna_height_m, vertical_half_angle_degrees, status, battery, last_report_time, created_at, updated_at) VALUES (4, 'radar-rail-01', '铁路路基形变雷达', 'MILLIMETER_WAVE_RADAR', 'RADAR-2026-RAIL-001', 113.0245478, 23.7220349, 27.572, 222.521, -3.645, 620.000, 30.000, 10.000, 15.000, 'ONLINE', 100.0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO device_point (id, device_id, point_id, target_code, azimuth_degrees, elevation_degrees, slant_range_m, reflector_height_m, line_of_sight, minimum_clearance_m, calibration_status, calibrated_at, valid_from, calibration_note) VALUES
(24, 4, 15, 'radar-rail-01-P-RW01', 250.581, -2.641, 525.416, 2.500, TRUE, 1.582, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '程序化结构体视线校验通过'),
(25, 4, 16, 'radar-rail-01-P-RW02', 246.691, -2.808, 441.524, 2.500, TRUE, 3.274, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '程序化结构体视线校验通过'),
(26, 4, 17, 'radar-rail-01-P-RW03', 241.015, -3.027, 360.608, 2.500, TRUE, 3.585, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '程序化结构体视线校验通过'),
(27, 4, 18, 'radar-rail-01-P-RW04', 232.204, -3.308, 285.212, 2.500, TRUE, 4.119, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '程序化结构体视线校验通过'),
(28, 4, 19, 'radar-rail-01-P-RW05', 217.727, -3.599, 221.061, 2.500, TRUE, 1.813, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '程序化结构体视线校验通过'),
(29, 4, 20, 'radar-rail-01-P-RW06', 194.460, -3.585, 180.562, 2.500, TRUE, 1.272, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '程序化结构体视线校验通过');
INSERT INTO digital_twin_scene (id, project_id, enabled, asset_type, coordinate_mode, asset_url, asset_version, asset_sha256, anchor_longitude, anchor_latitude, anchor_height, heading_degrees, pitch_degrees, roll_degrees, model_scale, camera_heading_degrees, camera_pitch_degrees, camera_range, maximum_screen_error, maximum_memory_mb, max_heat_points, label_distance, created_at, updated_at) VALUES (3, 4, TRUE, 'GLB', 'ENU', '/models/qingyuan-railway/qingyuan-railway.glb', 'qingyuan-railway-1.0.0', '1927c4725b79dc55e3d026c593058354e4ac1affd5a166b85bc3ac2f1ce32b49', 113.0219000, 23.7205000, 8.000, 0, 0, 0, 1, 300.000, -24.000, 780.000, 16, 512, 200, 2000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- ---------- 郊外工厂（罐区与厂房）（project 5） ----------
INSERT INTO project (id, organization_id, name, code, location, description, created_at, updated_at) VALUES (5, 1, '郊外工厂形变监测', 'PRJ-FACTORY', '清远市郊 工业园西区', '离线真实地形 + 程序化厂房/罐区；形变数据为模拟', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO scene (id, project_id, name, type, description, created_at, updated_at) VALUES (6, 5, '郊外工厂形变监测场景', 'PLANT', '郊外工厂（罐区与厂房）', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO monitor_object (id, scene_id, name, type, description, created_at, updated_at) VALUES (6, 6, '郊外工厂（罐区与厂房）', 'PLANT_AREA', '程序化结构体 + 真实地形', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO monitor_point (id, object_id, code, name, type, longitude, latitude, altitude, enabled, created_at, updated_at) VALUES
(21, 6, 'P-FC01', '厂房测点1', 'DEFORMATION', 113.0773137, 23.6944334, 19.124, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(22, 6, 'P-FC02', '厂房测点2', 'DEFORMATION', 113.0777059, 23.6942709, 19.124, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(23, 6, 'P-FC03', '厂房测点3', 'DEFORMATION', 113.0781961, 23.6935485, 19.124, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(24, 6, 'P-FC04', '罐区测点1', 'DEFORMATION', 113.0774117, 23.6936388, 19.124, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(25, 6, 'P-FC05', '罐区测点2', 'DEFORMATION', 113.0786863, 23.6942528, 19.124, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(26, 6, 'P-FC06', '烟囱测点', 'DEFORMATION', 113.0789315, 23.6940451, 19.124, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO metric (id, point_id, code, name, unit, sort_order, created_at, updated_at) VALUES
(124, 21, 'defo_mm', '累计形变', 'mm', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(125, 21, 'rate_mm_d', '形变速率', 'mm/d', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(126, 22, 'defo_mm', '累计形变', 'mm', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(127, 22, 'rate_mm_d', '形变速率', 'mm/d', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(128, 23, 'defo_mm', '累计形变', 'mm', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(129, 23, 'rate_mm_d', '形变速率', 'mm/d', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(130, 24, 'defo_mm', '累计形变', 'mm', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(131, 24, 'rate_mm_d', '形变速率', 'mm/d', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(132, 25, 'defo_mm', '累计形变', 'mm', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(133, 25, 'rate_mm_d', '形变速率', 'mm/d', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(134, 26, 'defo_mm', '累计形变', 'mm', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(135, 26, 'rate_mm_d', '形变速率', 'mm/d', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO device (id, code, name, type, serial_no, longitude, latitude, altitude, heading_degrees, pitch_degrees, detection_range_m, half_angle_degrees, antenna_height_m, vertical_half_angle_degrees, status, battery, last_report_time, created_at, updated_at) VALUES (5, 'radar-factory-01', '厂区形变雷达', 'MILLIMETER_WAVE_RADAR', 'RADAR-2026-FACTORY-001', 113.0800590, 23.6953544, 17.833, 234.201, -2.103, 520.000, 30.000, 10.000, 15.000, 'ONLINE', 100.0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO device_point (id, device_id, point_id, target_code, azimuth_degrees, elevation_degrees, slant_range_m, reflector_height_m, line_of_sight, minimum_clearance_m, calibration_status, calibrated_at, valid_from, calibration_note) VALUES
(30, 5, 21, 'radar-factory-01-P-FC01', 249.984, -1.194, 298.065, 2.500, TRUE, 4.202, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '程序化结构体视线校验通过'),
(31, 5, 22, 'radar-factory-01-P-FC02', 243.435, -1.326, 268.400, 2.500, TRUE, 4.612, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '程序化结构体视线校验通过'),
(32, 5, 23, 'radar-factory-01-P-FC03', 223.531, -1.289, 275.932, 2.500, TRUE, 4.961, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '程序化结构体视线校验通过'),
(33, 5, 24, 'radar-factory-01-P-FC04', 234.866, -1.077, 330.210, 2.500, TRUE, 5.757, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '程序化结构体视线校验通过'),
(34, 5, 25, 'radar-factory-01-P-FC05', 228.930, -1.915, 185.802, 2.500, TRUE, 4.224, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '程序化结构体视线校验通过'),
(35, 5, 26, 'radar-factory-01-P-FC06', 218.418, -1.921, 185.172, 2.500, TRUE, 4.751, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '程序化结构体视线校验通过');
INSERT INTO digital_twin_scene (id, project_id, enabled, asset_type, coordinate_mode, asset_url, asset_version, asset_sha256, anchor_longitude, anchor_latitude, anchor_height, heading_degrees, pitch_degrees, roll_degrees, model_scale, camera_heading_degrees, camera_pitch_degrees, camera_range, maximum_screen_error, maximum_memory_mb, max_heat_points, label_distance, created_at, updated_at) VALUES (4, 5, TRUE, 'GLB', 'ENU', '/models/qingyuan-factory/qingyuan-factory.glb', 'qingyuan-factory-1.0.0', 'e3d205f555d29405f9841ea57c9663b93ae6143e4f91b7efc177967ac8faba43', 113.0780000, 23.6940000, 10.000, 0, 0, 0, 1, 315.000, -32.000, 700.000, 16, 512, 200, 2000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
