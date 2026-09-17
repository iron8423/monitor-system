-- =====================================================================
-- V15 标定失效与时间可信度（清单第 09、10 条）
--
-- 一、device_point 的失效痕迹
--
-- V11 只给 calibration_status 定义了 ACTIVE/PENDING，于是设备被移动、朝向被调整之后，
-- 那条按**旧位置**量出来的标定只能继续沿用 ACTIVE——它说的是一句已经不成立的话
-- （清单第 09 条）。本次在 Java 侧新增 INVALID 取值，这里补上承载「什么时候、因为什么、
-- 被谁」作废的三个列。
--
-- 为什么**不复用 valid_to** 来记失效：MyBatis-Plus 的 updateById 跳过 null 字段，
-- valid_to 一旦写过就再也清不掉（这一点在本次改造中一并修掉，见 DevicePointMapper#applyCalibration），
-- 拿它当失效标记会让那条绑定**永久**无法重新生效。同 V14 对 alarm.open_key 的处理思路：
-- 用一个专用列表达专用语义，不要拿既有列兼职。
--
-- 为什么**不加索引**：失效查询走的是
--   WHERE device_id = ? AND calibration_status = 'ACTIVE'
-- 而 V11 已有的 idx_device_point_calibration (device_id, calibration_status, line_of_sight)
-- 正好覆盖前两列。再加一条是重复索引。
--
-- 为什么**不做存量回填**：无法反推「设备的几何上一次是什么时候改的」。
-- 7 条种子标定（V11）保持 ACTIVE；第一次有人 PUT 挪动 radar-001/002 时它们会被正常退休，
-- 那正是期望行为。
--
-- 二、measurement.created_at
--
-- 本列 V1 就已建好（可空、无默认），但 Measurement 实体里**没有这个字段**，
-- 全仓零写入点，所以一直是 NULL。本次让它有值。
--
-- 注意它**不是**「网关接收时间」：契约（docs/message-contract.md）里没有承载网关时间的字段，
-- IngestMessage 上也没有，凭空用 now() 造一个只会得到 receive_time 的副本。
-- 它是「**平台写入这一行的时刻**」，能给出的唯一可诊断量是
--   created_at - receive_time = 平台从收到报文到落库的处理时延。
-- 将来与网关方改契约补上真正的网关时间戳时，不要在这一列上做文章，另开一列。
--
-- 无需 DDL：列已存在。
-- =====================================================================

ALTER TABLE device_point ADD COLUMN invalidated_at     TIMESTAMP;
ALTER TABLE device_point ADD COLUMN invalidated_reason VARCHAR(64);
ALTER TABLE device_point ADD COLUMN invalidated_by     VARCHAR(64);

-- 列语义（刻意不写 COMMENT ON：本仓 15 个迁移一个都没用过它，
-- 而 H2(dev/验收) / PostgreSQL(生产) / Flyway 三方都要吃这条语句，为一个注释去开一条没验证过的路不划算）：
--   invalidated_at     NULL = 未被主动失效；非空 = 失效时刻
--   invalidated_reason 短代码：DEVICE_POSE_CHANGED / POINT_MOVED / 人工停用白名单值
--   invalidated_by     操作者；几何变更由系统判定时，记改档案的那个 ADMIN
