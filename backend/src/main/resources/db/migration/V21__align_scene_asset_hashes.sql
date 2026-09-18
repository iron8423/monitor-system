-- =====================================================================
-- V21 对齐三个演示场景的资产哈希（V20 的一次历史遗留）
--
-- 背景：V20 首次落到开发库之后，资产生成器又修了一个 bug——结构体 primitive 的
-- bufferView 索引错位（指向了纹理），导致桥梁/铁路/厂房在浏览器里整块不显示。
-- 三个 GLB 因此重建、SHA-256 变了，V20 的**文件**随之更新；但那个库已经执行过 V20，
-- 记录的仍是旧校验和，于是下一次启动 Flyway 直接报
-- `Migration checksum mismatch for migration version 20`。
--
-- 这是「迁移不可变」被违反的一次实证，处理方式分两层：
--   ① 对已执行过 V20 的库：做一次 `flyway repair` 语义的操作，把 schema_history 里
--      V20 的校验和对齐到当前文件（只改校验和，不重放 SQL——重放会撞主键）；
--   ② 本迁移把 digital_twin_scene.asset_sha256 更新为**当前** GLB 的真实哈希，
--      让库里的记录与盘上的文件一致；
--   ③ 工具侧补了防线：tools/terrain_asset/emit_scene_migration.py 默认拒绝覆盖已存在的
--      迁移文件（要覆盖必须显式 --force），从源头堵住「重跑生成器改写已执行迁移」。
--
-- 对全新库：V20 插入的就是同样的值，本迁移是幂等空操作。
-- 记录见 docs/daily/2026-09-18-A.md 的「八、一次自己踩的坑」。
-- =====================================================================

UPDATE digital_twin_scene
SET asset_sha256 = 'ea1600647e4d54dedfd1a752485e0cde34b160ffff5946e673dbc888631f3ad6',
    updated_at = CURRENT_TIMESTAMP
WHERE project_id = 3;

UPDATE digital_twin_scene
SET asset_sha256 = 'c72e0bcf83012e3085f88966508f2183c7d1a55526e8443fa84c57ece7ee3862',
    updated_at = CURRENT_TIMESTAMP
WHERE project_id = 4;

UPDATE digital_twin_scene
SET asset_sha256 = 'dda1e8ad8515209a58a7ce6919aa3148bf254bb3ad1d1c7b2ed3bc24105ff1d9',
    updated_at = CURRENT_TIMESTAMP
WHERE project_id = 5;
