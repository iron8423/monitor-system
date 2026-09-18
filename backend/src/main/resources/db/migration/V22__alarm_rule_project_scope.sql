-- =====================================================================
-- V22 告警规则作用域（复查清单 P0-1）+ 演示设备播种时间修正（P0-2）
--
-- 一、为什么规则需要「项目」这一层
--
-- V2/V4 的三条种子规则是**全局规则**（point_id IS NULL），当时的系统只有一个场景（项目 1
-- 灰库/库区边坡），"全局"与"本项目"是同一件事。V20 之后有 4 个演示项目（山地边坡/桥梁/
-- 铁路/工厂），同一个 ±3mm、+5mm 会同时套到桥梁、路基、储罐基础上——阈值只对上了"物理量纲"，
-- 没有对上"结构"。演示阶段是噪音，投产阶段是误报/漏报。
--
-- 作用域三档（判定顺序见 AlarmEngine#enabledRulesFor）：
--   point_id 非空                -> 该测点专属（project_id 只是存档，不参与匹配）
--   point_id 空 + project_id 非空 -> 该项目下所有测点
--   point_id 空 + project_id 空   -> 全局（兼容旧数据；接口不再默认产出这一档，
--                                     管理端选择"全部项目"时才会写出来）
--
-- 为什么用**项目**而不是场景/对象：数据范围隔离（V8 DataScopeService）的粒度就是项目，
-- 规则的可见性要跟它同源，否则会出现"看得到项目、看得到规则、却不知道规则管哪一段"。
-- 需要更细的粒度时用 point_id，不要在这里发明第四档。
--
-- 二、三条种子规则绑到项目 1
--
-- 只在 id 与形状都对得上时才改（id IN (1,2,3) 且仍是全局规则）：这套件的种子 id 是 V2/V4
-- 显式指定的，若某套库上有人删过、改过，这里宁可不改也不能把一条人工建的全局规则
-- 悄悄挪到项目 1 名下。
--
-- 三、P0-2：演示雷达的 last_report_time
--
-- V20 把三个新场景的设备播种成 last_report_time = CURRENT_TIMESTAMP（"刚刚上报过"），
-- 而它们从未真的上报过——5 分钟后 DeviceAlarmMonitor 必然按离线开警情
--（判据 DeviceStatusPolicy.OFFLINE_MINUTES = 5）。DeviceAlarmMonitor 的设计里
-- "从未上报的设备不告警"（last_report_time IS NULL），那才是这三台设备建档时的真实状态。
--
-- 只在**确实没有 measurement 行**时才置空：跑过模拟器（tools/radar_simulator/
-- seed_all_scenes.sh）的库上，last_report_time 是接入路径真实写上去的，
-- 覆盖它等于把"昨天还在报数"改写成"从没报过数"，那是**另一个**方向的谎。
-- measurement.device_id 存的是设备**编码**（V1 列类型 VARCHAR(64)，写入点见 IngestService），
-- 所以这里按 code 匹配，不按 device.id。
--
-- 四、顺带结案由这条播种错误开出的离线警情
--
-- 判定条件与上面同源（设备从未上报），所以只会关掉"生来就是错的"那几条。
-- 关闭必须同时把 open_key 置回 NULL（V14 的维护义务）：否则该键位被永久占住，
-- 这台设备此后**再也开不出**离线警情——一条僵尸警情会升级成"这台设备从此不报离线"。
-- 留一条 alarm_action 说明是谁关的、为什么关，而不是静默改写历史。
-- =====================================================================

-- ---------- 一、alarm_rule 增加项目作用域 ----------
ALTER TABLE alarm_rule ADD COLUMN project_id BIGINT;

-- 引擎的热路径是 "enabled + metric_code + (point_id 为空或等于本点)"，
-- 项目列只在这之后做一次内存过滤；索引服务的是管理端列表与将来的按项目查询。
CREATE INDEX idx_alarm_rule_project ON alarm_rule (project_id);

-- ---------- 二、三条种子规则绑到项目 1（山地边坡） ----------
UPDATE alarm_rule
   SET project_id = 1, updated_at = CURRENT_TIMESTAMP
 WHERE id IN (1, 2, 3)
   AND point_id IS NULL
   AND project_id IS NULL;

-- ---------- 三、从未上报的演示设备：last_report_time 置空 ----------
UPDATE device
   SET last_report_time = NULL, updated_at = CURRENT_TIMESTAMP
 WHERE code IN ('radar-bridge-01', 'radar-rail-01', 'radar-factory-01')
   AND code NOT IN (SELECT m.device_id FROM measurement m WHERE m.device_id IS NOT NULL);

-- ---------- 四、结案"播种即离线"的警情 ----------
INSERT INTO alarm_action (alarm_id, action_type, operator, note, created_at)
SELECT a.id, 'resolve', 'system',
       'V22 迁移：该设备从未实际上报（last_report_time 为空），离线警情系 V20 演示播种时间戳所致，自动结案',
       CURRENT_TIMESTAMP
  FROM alarm a
  JOIN device d ON d.id = a.device_id
 WHERE a.alarm_type = 'DEVICE'
   AND a.alarm_reason = 'OFFLINE'
   AND a.status NOT IN ('RESOLVED', 'FALSE_ALARM')
   AND d.last_report_time IS NULL
   AND d.code IN ('radar-bridge-01', 'radar-rail-01', 'radar-factory-01');

UPDATE alarm
   SET status = 'RESOLVED', resolved_at = CURRENT_TIMESTAMP,
       open_key = NULL, updated_at = CURRENT_TIMESTAMP
 WHERE alarm_type = 'DEVICE'
   AND alarm_reason = 'OFFLINE'
   AND status NOT IN ('RESOLVED', 'FALSE_ALARM')
   AND device_id IN (SELECT id FROM device
                      WHERE last_report_time IS NULL
                        AND code IN ('radar-bridge-01', 'radar-rail-01', 'radar-factory-01'));
