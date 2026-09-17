-- V16：在**已经建过 uk_alarm_open_key** 的库上，把 V14 的「给存量未解除警情打键」安全地补跑一遍。
--
-- 为什么需要：V14 只在它执行的那一瞬间给存量未解除行打键。此后若旧后端镜像（jar 里没有 open_key
-- 的写入路径）连上同一张库，Flyway 把 V14 之后的迁移当 future 忽略，而旧代码 INSERT 的警情根本没有
-- open_key 这一列——它们以 NULL 键落库。PostgreSQL 与 H2 都把多个 NULL 视作互不相同，于是
-- uk_alarm_open_key 对这批行完全不设防，「同一测点同一测项至多一条未解除警情」这条不变量在回滚
-- 窗口里静默失效。见 docs/3D数字孪生_生产候选部署与验收.md §3.5。
--
-- 为什么不能改 V14：Flyway 会比对已应用迁移的校验和，改一个字应用就起不来。
-- 为什么用迁移而不是手工 UPDATE：手工只修得了一份库，修不了生产、也修不了从备份恢复出来的库。
-- 本迁移**幂等**：跑第二次时 ① 无可补、② 无可释放、③④ 无可收敛、⑤ 无可打键，全是空操作。
--
-- ⚠ 下面五步的顺序是承重的。②③④ 存在的唯一理由，是让 ⑤ 不可能撞唯一索引：迁移是原子的，
-- ⑤ 一旦报 23505，整个迁移失败、**应用起不来**。V14 敢在第 40-47 行直接跑无护栏的 UPDATE，
-- 是因为它的索引在第 69 行才建——打键那一刻索引还不存在；V16 面对的是索引已经建好的库，
-- 没有那个余地。四步合起来保证「打键之后不存在两条非空且相等的 open_key」：
--   * 两条都来自 ⑤：键相等 ⇒ alarm_type 与键列都相等 ⇒ ③ 已经把 id 较大的那条关掉了；
--   * 一条来自 ⑤、一条是已打键的行：④ 会把空键的那条关掉（按字符串比，不假设键与列自洽）；
--   * 两条都已打键：V14 起索引就在，本来就不可能；
--   * 已打键 + 终态行：终态行不参与唯一性，而且 ② 已经把它的键释放掉了。

-- ① 补 metric_code（只填空）。V14 第 35-37 行的重放，**加了一个 metric_code IS NULL**：
--   * 新代码开的警情在落库时就写好了测项，不该被规则表的**当前**值覆盖；
--   * 更要紧的是：AlarmRuleService.delete 是物理删除，rule_id 悬空的行取不到测项，V14 那条语句
--     会把它的 metric_code 写成 NULL——对一行本来完好的新数据来说，那不是补空，是**销毁**。
UPDATE alarm SET metric_code = (
    SELECT r.metric_code FROM alarm_rule r WHERE r.id = alarm.rule_id
) WHERE alarm_type = 'POINT' AND rule_id IS NOT NULL AND metric_code IS NULL;

-- ② 释放僵尸键位：终态行上的 open_key 置回 NULL。
-- 成因：旧镜像的关闭路径走 updateById，而 open_key 不在它的实体上（updateById 跳过 null 字段），
-- 这个键写不掉；于是那条**早已解除**的警情永久占着键位，把该测点该测项后续所有警情挡在门外
-- （V14 第 21-25 行写明的维护义务）。这正是部署文档 §3.5 的「应急解封」，只是把逐行手改改成全表。
-- 这条语句本身不可能制造冲突：把键置成 NULL 永远不会违反唯一索引。
UPDATE alarm SET open_key = NULL, updated_at = CURRENT_TIMESTAMP
 WHERE open_key IS NOT NULL
   AND status IN ('RESOLVED', 'FALSE_ALARM');

-- ③ 收敛（甲）：同一键位有一批未解除的**空键**行时，留 id 最小的一条，其余关闭并补留痕。
-- 口径与 V14 第 49-66 行完全一致：留最早一条（先开的才是真实发生过的那次）、按系统自动解除关闭、
-- 补一条 recover 留痕让「这条为什么被关了」在时间线上查得到；两条语句的顺序也一致（先留痕再改状态）。
-- 键位在这里用**键列的结构比较**表达，而不是拼字符串：alarm_type 相同的前提下，
-- 'D:'||device_id||':'||alarm_reason 相等 ⇔ (device_id, alarm_reason) 相等，'P:' 同理。
-- 两边都要求本行的键列非空——键列不全的行根本打不出键（⑤ 的 ELSE NULL），
-- 那种行之间不存在键位冲突，不该被这里关掉。
--
-- 注意别名：下面 INSERT 那条的 SELECT 里有 `FROM alarm a`，所以它写 `a.id`；而 UPDATE 的目标表
-- **没有别名**，只能写 `alarm.id`。两处写法不一致是必需的，不是笔误——H2 会对 `a.id` 报
-- 42122（Column "A.ID" not found），PostgreSQL 会报 missing FROM-clause entry for table "a"。
INSERT INTO alarm_action (alarm_id, action_type, operator, note, created_at)
SELECT a.id, 'recover', 'system',
       'V16 迁移：同一测点同一测项（或同一设备同一成因）存在多条未打键的未解除警情，保留最早一条，本条自动关闭',
       CURRENT_TIMESTAMP
  FROM alarm a
 WHERE a.open_key IS NULL
   AND a.status NOT IN ('RESOLVED', 'FALSE_ALARM')
   AND EXISTS (
        SELECT 1 FROM alarm b
         WHERE b.id < a.id
           AND b.open_key IS NULL
           AND b.status NOT IN ('RESOLVED', 'FALSE_ALARM')
           AND b.alarm_type = a.alarm_type
           AND (   (a.alarm_type = 'DEVICE' AND a.device_id IS NOT NULL AND a.alarm_reason IS NOT NULL
                    AND b.device_id = a.device_id AND b.alarm_reason = a.alarm_reason)
                OR (a.alarm_type = 'POINT'  AND a.point_id  IS NOT NULL AND a.metric_code  IS NOT NULL
                    AND b.point_id = a.point_id AND b.metric_code = a.metric_code)));

UPDATE alarm SET status = 'RESOLVED', resolved_at = CURRENT_TIMESTAMP,
                 updated_at = CURRENT_TIMESTAMP
 WHERE open_key IS NULL
   AND status NOT IN ('RESOLVED', 'FALSE_ALARM')
   AND EXISTS (
        SELECT 1 FROM alarm b
         WHERE b.id < alarm.id
           AND b.open_key IS NULL
           AND b.status NOT IN ('RESOLVED', 'FALSE_ALARM')
           AND b.alarm_type = alarm.alarm_type
           AND (   (alarm.alarm_type = 'DEVICE' AND alarm.device_id IS NOT NULL
                    AND alarm.alarm_reason IS NOT NULL
                    AND b.device_id = alarm.device_id AND b.alarm_reason = alarm.alarm_reason)
                OR (alarm.alarm_type = 'POINT'  AND alarm.point_id  IS NOT NULL
                    AND alarm.metric_code IS NOT NULL
                    AND b.point_id = alarm.point_id AND b.metric_code = alarm.metric_code)));

-- ④ 收敛（乙）：本行补键之后，那个键位已经被一条**未解除且已打键**的行占着——让位。
-- 用**字符串比较**（拼出本行的键去比 open_key）而不是比键列：存量已打键的行里可能有键与列不自洽的
-- 数据（手工改过、或由别处写入），只有字符串真的相等才会撞索引。CASE 的 ELSE NULL 让键列不全的行
-- 天然不匹配（NULL = 任何值 都是 UNKNOWN），不需要另加判据。
-- 终态行不在这里处理：它们的键由 ② 释放，所以这里只认**未解除**的占位者。
INSERT INTO alarm_action (alarm_id, action_type, operator, note, created_at)
SELECT a.id, 'recover', 'system',
       'V16 迁移：该键位已被另一条未解除警情占用（旧镜像回滚窗口的遗留），本条自动关闭',
       CURRENT_TIMESTAMP
  FROM alarm a
 WHERE a.open_key IS NULL
   AND a.status NOT IN ('RESOLVED', 'FALSE_ALARM')
   AND EXISTS (
        SELECT 1 FROM alarm b
         WHERE b.open_key IS NOT NULL
           AND b.status NOT IN ('RESOLVED', 'FALSE_ALARM')
           AND b.open_key = CASE
                WHEN a.alarm_type = 'POINT'  AND a.point_id  IS NOT NULL AND a.metric_code  IS NOT NULL
                    THEN 'P:' || a.point_id || ':' || a.metric_code
                WHEN a.alarm_type = 'DEVICE' AND a.device_id IS NOT NULL AND a.alarm_reason IS NOT NULL
                    THEN 'D:' || a.device_id || ':' || a.alarm_reason
                ELSE NULL
            END);

UPDATE alarm SET status = 'RESOLVED', resolved_at = CURRENT_TIMESTAMP,
                 updated_at = CURRENT_TIMESTAMP
 WHERE open_key IS NULL
   AND status NOT IN ('RESOLVED', 'FALSE_ALARM')
   AND EXISTS (
        SELECT 1 FROM alarm b
         WHERE b.open_key IS NOT NULL
           AND b.status NOT IN ('RESOLVED', 'FALSE_ALARM')
           AND b.open_key = CASE
                WHEN alarm.alarm_type = 'POINT' AND alarm.point_id IS NOT NULL
                     AND alarm.metric_code IS NOT NULL
                    THEN 'P:' || alarm.point_id || ':' || alarm.metric_code
                WHEN alarm.alarm_type = 'DEVICE' AND alarm.device_id IS NOT NULL
                     AND alarm.alarm_reason IS NOT NULL
                    THEN 'D:' || alarm.device_id || ':' || alarm.alarm_reason
                ELSE NULL
            END);

-- ⑤ 打键本身：V14 第 40-47 行的原样重放，只多一个 open_key IS NULL（已打过键的行一个字节不动）。
-- 不写 updated_at：与 V14 那次回填一致——它只补一个此前不存在的列，不是一次业务变更。
-- 跑完之后仍为 NULL 的行 = 键成分不全的行（点号/测项，或设备号/成因，缺一），它们本来就不参与
-- 唯一性。请用部署文档 §3.5 的残留查询核对这批行确实属于那一类，不要当成迁移漏跑了。
UPDATE alarm SET open_key = CASE
        WHEN alarm_type = 'POINT'  AND point_id  IS NOT NULL AND metric_code  IS NOT NULL
            THEN 'P:' || point_id || ':' || metric_code
        WHEN alarm_type = 'DEVICE' AND device_id IS NOT NULL AND alarm_reason IS NOT NULL
            THEN 'D:' || device_id || ':' || alarm_reason
        ELSE NULL
    END
 WHERE open_key IS NULL
   AND status NOT IN ('RESOLVED', 'FALSE_ALARM');
