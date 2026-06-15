-- kxj: 修复 V6/V7 历史迁移可能未实际应用的缺失列, 使用 IF NOT EXISTS 幂等补齐
-- 背景: V6 曾因文件内容被替换导致 checksum mismatch, repair 后 schema_history 已同步,
-- 但旧版 V6 应用时未真正添加 level 列, 故此处幂等补齐 level/times/template 三列
ALTER TABLE alert_rule
    ADD COLUMN IF NOT EXISTS level VARCHAR(16) NOT NULL DEFAULT 'warning';

ALTER TABLE alert_rule
    ADD COLUMN IF NOT EXISTS times INTEGER NOT NULL DEFAULT 1;

ALTER TABLE alert_rule
    ADD COLUMN IF NOT EXISTS template VARCHAR(1024);
