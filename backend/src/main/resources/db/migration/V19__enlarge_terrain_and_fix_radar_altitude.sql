-- =====================================================================
-- V19 放大真实地形场景 + 修正雷达高程（V18 的一处单位口径 bug）
--
-- 两件事，分开说：
--
-- ① 【修 bug】V18 把雷达"抬到天上"了。
--    `build_terrain_asset.py` 在写雷达本地坐标时把**绝对高程**（基准面 + 相对高度）
--    填进了 `localPosition[2]`，而生成迁移时又加了一次基准面，于是
--    device.altitude = 基准面 × 2 + 相对高度：两台雷达整整高出场景 107m，
--    在大屏上表现为"雷达和视场扇面浮在半空、测点却贴在坡面上"。
--    本版已修生成器（相对高度归相对高度、绝对高程另存 absoluteAltitude），
--    下面两台雷达的 altitude 是按修好后的口径重算的值。
--
-- ② 【放大场景】320×240m → 1000×750m。
--    V18 那版场景太小：真实起伏只有 96m、测点与雷达全挤在 300m 见方的范围内，
--    看不出地形关系。V2 用同一片山坡向外扩到 1000×750m（真实起伏 237m，
--    4m 网格、4096×3072 卫星纹理），并把两台雷达放到**监测区之外**的稳定地面上：
--      · 北侧雷达：坡体东北侧的台地 (150, 300)，俯瞰山脊与上部坡面（226.7° / +9.5°）；
--      · 南侧雷达：坡脚谷地 (100, -300)，仰视滑坡体与坡脚（7.6° / +10.7°）。
--    7 个测点仍按"山脊 3 点 + 滑坡体 4 点"分布，但落在整片坡面上
--    （东西 350m、南北 350m 的范围），每台雷达的目标都在 ±30° 水平视场、
--    ±15° 垂直视场内，且逐米步进的视线校验全部通过（见 coverage.json）。
--
-- 旧资产 `qingyuan-hillside-1.0.0` 与 `mountain-demo-2.0.0` 都保留，回退只需改
-- digital_twin_scene 的三列。
--
-- ⚠ 下面数值由 tools/terrain_asset/build_terrain_asset.py 解算，不是手填：
--   资产版本：qingyuan-hillside-2.0.0
--   GLB SHA-256：d0087127c07f1daecbbbe938f7de0ace029b58d36807d6ce2969da8c1c9f4b72
-- =====================================================================

UPDATE monitor_point SET longitude = 113.0498585, latitude = 23.7612658, altitude = 211.734, updated_at = CURRENT_TIMESTAMP WHERE id = 1;
UPDATE monitor_point SET longitude = 113.0503490, latitude = 23.7603629, altitude = 198.757, updated_at = CURRENT_TIMESTAMP WHERE id = 2;
UPDATE monitor_point SET longitude = 113.0513300, latitude = 23.7599114, altitude = 180.660, updated_at = CURRENT_TIMESTAMP WHERE id = 3;
UPDATE monitor_point SET longitude = 113.0518205, latitude = 23.7599114, altitude = 171.009, updated_at = CURRENT_TIMESTAMP WHERE id = 4;
UPDATE monitor_point SET longitude = 113.0518205, latitude = 23.7590086, altitude = 141.753, updated_at = CURRENT_TIMESTAMP WHERE id = 5;
UPDATE monitor_point SET longitude = 113.0518205, latitude = 23.7581057, altitude = 107.385, updated_at = CURRENT_TIMESTAMP WHERE id = 6;
UPDATE monitor_point SET longitude = 113.0532919, latitude = 23.7581057, altitude = 107.046, updated_at = CURRENT_TIMESTAMP WHERE id = 7;

-- 雷达高程：altitude 是**地面**海拔（雷达头由前端按 antenna_height_m 画在上方）。
-- 修正后 = 基准面(43m) + 本地相对高度，与 GLB 的本地 +Z 完全对齐。
UPDATE device SET longitude = 113.0528015, latitude = 23.7621687, altitude = 131.001, heading_degrees = 226.683, pitch_degrees = 9.484, detection_range_m = 620.000, half_angle_degrees = 30.000, vertical_half_angle_degrees = 15.000, antenna_height_m = 10.000, updated_at = CURRENT_TIMESTAMP WHERE id = 1;
UPDATE device SET longitude = 113.0523110, latitude = 23.7567513, altitude = 71.275, heading_degrees = 7.628, pitch_degrees = 10.740, detection_range_m = 620.000, half_angle_degrees = 30.000, vertical_half_angle_degrees = 15.000, antenna_height_m = 10.000, updated_at = CURRENT_TIMESTAMP WHERE id = 2;

DELETE FROM device_point WHERE device_id IN (1, 2);

INSERT INTO device_point (
    id, device_id, point_id, target_code,
    azimuth_degrees, elevation_degrees, slant_range_m, reflector_height_m,
    line_of_sight, minimum_clearance_m, calibration_status,
    calibrated_at, valid_from, calibration_note
) VALUES
(11, 1, 1, 'radar-001-P-HK01', 251.565, 13.039, 324.597, 2.500, TRUE, 1.438, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产 V2 视线校验通过'),
(12, 1, 2, 'radar-001-P-HK02', 231.340, 10.659, 325.777, 2.500, TRUE, 1.150, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产 V2 视线校验通过'),
(13, 1, 3, 'radar-001-P-HK03', 210.964,  8.228, 294.580, 2.500, TRUE, 3.084, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产 V2 视线校验通过'),
(14, 1, 4, 'radar-001-P-BP01', 201.801,  6.884, 271.213, 2.500, TRUE, 2.883, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产 V2 视线校验通过'),
(15, 2, 5, 'radar-002-P-BP02', 348.690, 13.875, 262.614, 2.500, TRUE, 2.869, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产 V2 视线校验通过'),
(16, 2, 6, 'radar-002-P-BP03', 341.565, 10.256, 160.681, 2.500, TRUE, 2.839, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产 V2 视线校验通过'),
(17, 2, 7, 'radar-002-P-BP04',  33.690,  8.912, 182.481, 2.500, TRUE, 1.577, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '真实地形资产 V2 视线校验通过');

UPDATE digital_twin_scene
SET asset_url = '/models/qingyuan-hillside-v2/qingyuan-hillside-v2.glb',
    asset_version = 'qingyuan-hillside-2.0.0',
    asset_sha256 = 'd0087127c07f1daecbbbe938f7de0ace029b58d36807d6ce2969da8c1c9f4b72',
    anchor_longitude = 113.0513300,
    anchor_latitude = 23.7594600,
    anchor_height = 43.000,
    camera_heading_degrees = 315.000,
    camera_pitch_degrees = -30.000,
    camera_range = 1650.000,
    updated_at = CURRENT_TIMESTAMP
WHERE project_id = 1;
