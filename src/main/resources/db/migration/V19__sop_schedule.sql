-- kxj: 远期 - SOP 技能定时巡检表
-- AI 可创建 cron 巡检任务,执行 SOP 技能(daily_report / error_surge_diagnosis / jvm_oom_diagnosis)

CREATE TABLE IF NOT EXISTS sop_schedule (
    id              BIGSERIAL PRIMARY KEY,
    sop_name        VARCHAR(64)  NOT NULL,          -- 技能名: daily_report / error_surge_diagnosis / jvm_oom_diagnosis
    appid           BIGINT,                        -- 目标应用(可为空,技能内决定)
    cron_expression VARCHAR(64)  NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    last_run_time   TIMESTAMPTZ,
    next_run_time   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sop_schedule_enabled
    ON sop_schedule (enabled, next_run_time);

COMMENT ON TABLE  sop_schedule IS 'SOP 技能定时巡检表 - AI 创建 cron 任务,执行技能引擎';
COMMENT ON COLUMN sop_schedule.sop_name IS '技能名: daily_report / error_surge_diagnosis / jvm_oom_diagnosis';