-- V14：给 alarm 补「未解除唯一键」，把「同一测点同一测项至多一条未解除警情」从口头约定变成库级约束。
--
-- 背景（清单第 32 条）：AlarmEngine.findOpen 是典型的 check-then-act——先 SELECT 一条未解除警情，
-- 再决定触发 / 升级 / 解除。而 alarm 表此前**没有任何唯一约束**，两个并发请求都在 SELECT 里
-- 看到「没有未解除警情」时，各自 INSERT 一条，同一测点同一测项就出现了两条未解除警情。
-- 引擎 javadoc 里那句「至多一条」此前只是应用层约定，多实例部署下更是完全失效。
--
-- 做法：加一个**可空**的 open_key 列——未解除时等于 'P:<pointId>:<metricCode>'（测点警情）
-- 或 'D:<deviceId>:<reason>'（设备警情），进入终态时由应用层置回 NULL，然后对它建**普通唯一索引**。
--
-- 为什么是「可空 + 普通唯一索引」而不是「带 WHERE 的部分唯一索引」：
-- H2 2.3.232 不支持 `CREATE INDEX ... WHERE`（那是 PostgreSQL 专有），而同一份 V14
-- 要在 H2（本地 / 验收套件）与 PostgreSQL（生产）上都跑。两个库都把多个 NULL 视为互不相同，
-- 于是「只有未解除的行参与唯一性」这个语义可以原样移植，不需要方言分支。
--
-- 为什么另加 metric_code 列而不是直接用 rule_id：唯一性要的是「测点 + **测项**」，
-- 而测项此前只能靠 rule_id 间接表达（findOpen 传的就是「该测项的规则集」）。
-- 升级功能（V4 的种子规则）恰恰依赖同一测项下存在多条规则，所以对 (point_id, rule_id)
-- 建唯一键会把升级路径整个堵死——两条同测项不同等级的规则各自开一条警情，正是设计要避免的。
--
-- 维护义务（**新增写路径时必须一并维护**）：open_key 由应用层维护，四条关闭路径
-- （AlarmEngine.recover / AlarmService.act / DeviceAlarmMonitor.clear / DataQualityMonitor.clear）
-- 都必须把它置回 NULL，否则那条警情会永久占住键位，把该测点该测项**后续所有警情挡在门外**——
-- 一条僵尸警情会升级成「这条测项从此不再报警」。关闭统一走 AlarmMapper.closeAlarm，
-- NULL 只在那一处写（updateById 会因为「跳过 null 字段」而写不掉这个键，所以必须是显式 SQL）。

ALTER TABLE alarm ADD COLUMN metric_code VARCHAR(32);
ALTER TABLE alarm ADD COLUMN open_key    VARCHAR(96);

-- 回填 metric_code：测点警情从所属规则取。
-- rule_id 可空、且 AlarmRuleService.delete 是**物理删除**，所以存量库里必然有 rule_id 悬空的
-- 测点警情——那种行取不到测项，保持 NULL。同理 alarm_rule.metric_code 本身也可空。
-- 这几处 NULL 不是疏漏而是刻意的：拼出 'P:1001:null' 这种字面量键，会把一批互不相关的
-- 历史警情判成「同一条」，然后被下面的收敛逻辑误关掉。
UPDATE alarm SET metric_code = (
    SELECT r.metric_code FROM alarm_rule r WHERE r.id = alarm.rule_id
) WHERE alarm_type = 'POINT' AND rule_id IS NOT NULL;

-- 给**存量未解除**警情打键。终态的存量行保持 NULL（它们本来就不参与唯一性）。
UPDATE alarm SET open_key = CASE
        WHEN alarm_type = 'POINT'  AND point_id  IS NOT NULL AND metric_code  IS NOT NULL
            THEN 'P:' || point_id || ':' || metric_code
        WHEN alarm_type = 'DEVICE' AND device_id IS NOT NULL AND alarm_reason IS NOT NULL
            THEN 'D:' || device_id || ':' || alarm_reason
        ELSE NULL
    END
 WHERE status NOT IN ('RESOLVED', 'FALSE_ALARM');

-- 收敛存量重复行。唯一索引建在**已经**存在重复数据的表上会直接失败、应用起不来，
-- 而这里的重复正是上面那个 bug 的产物，所以必须在建索引前处理掉，不能留给「应该没有吧」。
-- 取舍（2026-09-16 定案）：保留**最早**（id 最小）的那条——先开的才是真实发生过的那次，
-- 后面的是并发重复建出来的；其余按系统自动解除关闭，并补一条 recover 留痕，
-- 让「这条为什么被关了」在时间线上查得到，而不是被静默改写。
-- 若反过来让迁移报错中止，首次升级会在现场被卡住，且那条路径只能靠手工 SQL 验证。
INSERT INTO alarm_action (alarm_id, action_type, operator, note, created_at)
SELECT a.id, 'recover', 'system',
       'V14 迁移：同一测点同一测项（或同一设备同一成因）存在重复未解除警情，保留最早一条，本条自动关闭',
       CURRENT_TIMESTAMP
  FROM alarm a
 WHERE a.open_key IS NOT NULL
   AND EXISTS (SELECT 1 FROM alarm b WHERE b.open_key = a.open_key AND b.id < a.id);

UPDATE alarm SET status = 'RESOLVED', resolved_at = CURRENT_TIMESTAMP,
                 open_key = NULL, updated_at = CURRENT_TIMESTAMP
 WHERE open_key IS NOT NULL
   AND EXISTS (SELECT 1 FROM alarm b WHERE b.open_key = alarm.open_key AND b.id < alarm.id);

-- 幂等键本体。普通唯一索引 + 可空列 = 只约束未解除的行。
CREATE UNIQUE INDEX uk_alarm_open_key ON alarm (open_key);

-- findOpen 此前按 (point_id, rule_id, status) 过滤而 point_id 上没有任何索引，
-- 每来一条测值都要扫一遍 alarm 表。这个索引同时服务 findOpen / openAlarmOf / 列表筛选。
CREATE INDEX idx_alarm_point_status ON alarm (point_id, status);
