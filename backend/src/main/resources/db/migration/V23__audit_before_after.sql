-- =====================================================================
-- V23 审计留痕补齐：变更前后值 + 失败也记（复查清单 P1-8）
--
-- 背景：V1 的 audit_log 只有 action / target_type / target_id / detail。
-- detail 存的是**入参 JSON**（AuditAspect 序列化），于是它只回答得了两件事：
-- 谁、什么时候、对什么做了什么。回答不了的两件恰恰是排查时最常问的：
--   · 「这一行**原来**是什么值」——入参只是新值，而且 updateById 会跳过 null 字段，
--     入参甚至连"新值有多少"都说不全；
--   · 「被拒绝/失败的写操作有没有发生过」——旧切面在 pjp.proceed() **之后**才落库，
--     方法一抛异常就什么都不留。想查"有没有人试图改过"时是空白。
--
-- 四列的分工：
--   before_json   操作前的原值（按主键查回来的整行；新建为空 = 本来就没有）
--   after_json    操作后的值（同样按主键查回来；删除为空 = 现在没有了）
--   result        SUCCESS / FAILED。**刻意不给默认值**：将来某个写路径忘了填，
--                 这里会是 NULL（"没标记"），而不是冒充一次成功
--   error_message 失败原因（业务异常的 message，便于回答"为什么被拒"）
--
-- 为什么不复用 detail：detail 是**入参**，语义已经固定并且前端在读它；
-- 把它改写成"变更摘要"会让历史行与现存前端同时错位。新列与它并存，各说各的。
--
-- 长度取 2048：本仓实体序列化后最长的是 Device（约 600 字符），留三倍余量；
-- 超过就截断并在末尾标记（见 AuditAspect），不引入 CLOB/TEXT ——
-- H2 与 PostgreSQL 的 TEXT 类型名不同，而这个仓库的规矩是同一份迁移跑两个库。
-- =====================================================================

ALTER TABLE audit_log ADD COLUMN before_json   VARCHAR(2048);
ALTER TABLE audit_log ADD COLUMN after_json    VARCHAR(2048);
ALTER TABLE audit_log ADD COLUMN result        VARCHAR(16);
ALTER TABLE audit_log ADD COLUMN error_message VARCHAR(512);

-- 存量行全部是"成功之后才写"的（旧切面的写入点就在 proceed() 之后），
-- 所以它们的结果是确定的 SUCCESS；不回填的话，审计列表里一屏历史行全是空结果，
-- 看的人会以为新列没生效。
UPDATE audit_log SET result = 'SUCCESS' WHERE result IS NULL;
