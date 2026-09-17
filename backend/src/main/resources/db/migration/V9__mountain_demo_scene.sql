-- =====================================================================
-- V9 离线低多边形山地演示场景
--
-- 只调整项目 1 的展示档案和 7 个既有测点位置，不改 id、归属、测项、设备绑定，
-- 所以实时上报、告警、回放与项目隔离链路仍沿用原有数据契约。
-- 经纬度/高程与 frontend/public/models/mountain-demo/points.json 同源。
-- =====================================================================

UPDATE project
SET name = '清远山地边坡形变监测',
    description = '完全离线的低多边形山地模拟场景，用于毫米波点形变雷达监测演示',
    updated_at = CURRENT_TIMESTAMP
WHERE id = 1;

UPDATE scene SET name = '山脊监测区', type = 'RIDGE', description = '主山脊稳定性监测（3 个测点）', updated_at = CURRENT_TIMESTAMP WHERE id = 1;
UPDATE scene SET name = '滑坡风险区', type = 'SLOPE', description = '滑坡凹槽与堆积体监测（4 个测点）', updated_at = CURRENT_TIMESTAMP WHERE id = 2;
UPDATE monitor_object SET name = '主山脊', type = 'RIDGE_BODY', description = '低多边形山体主山脊', updated_at = CURRENT_TIMESTAMP WHERE id = 1;
UPDATE monitor_object SET name = '滑坡体', type = 'LANDSLIDE_BODY', description = '模拟滑坡凹槽与坡脚堆积体', updated_at = CURRENT_TIMESTAMP WHERE id = 2;

-- 点号是设备报文和影像目录使用的稳定标识，仍为 P-HK01~03 / P-BP01~04，不改 code。
UPDATE monitor_point SET name = '山脊测点1', longitude = 113.0498978, latitude = 23.7209806, altitude = 60.064, updated_at = CURRENT_TIMESTAMP WHERE id = 1;
UPDATE monitor_point SET name = '山脊测点2', longitude = 113.0503293, latitude = 23.7212876, altitude = 54.498, updated_at = CURRENT_TIMESTAMP WHERE id = 2;
UPDATE monitor_point SET name = '山脊测点3', longitude = 113.0507706, latitude = 23.7213779, altitude = 44.342, updated_at = CURRENT_TIMESTAMP WHERE id = 3;
UPDATE monitor_point SET name = '滑坡体测点1', longitude = 113.0511040, latitude = 23.7212514, altitude = 41.318, updated_at = CURRENT_TIMESTAMP WHERE id = 4;
UPDATE monitor_point SET name = '滑坡体测点2', longitude = 113.0512805, latitude = 23.7209625, altitude = 52.195, updated_at = CURRENT_TIMESTAMP WHERE id = 5;
UPDATE monitor_point SET name = '滑坡体测点3', longitude = 113.0513492, latitude = 23.7206014, altitude = 39.085, updated_at = CURRENT_TIMESTAMP WHERE id = 6;
UPDATE monitor_point SET name = '滑坡体测点4', longitude = 113.0511825, latitude = 23.7202402, altitude = 37.263, updated_at = CURRENT_TIMESTAMP WHERE id = 7;

UPDATE device
SET name = '山地毫米波点形变雷达 1 号',
    longitude = 113.0495840,
    latitude = 23.7200596,
    updated_at = CURRENT_TIMESTAMP
WHERE id = 1;
