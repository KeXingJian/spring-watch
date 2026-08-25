-- kxj: AI 告警智能诊断报告表(P0)
-- FIRING 告警触发后,异步拉取前后 30min 指标窗口 + 错误日志 TopN,LLM 生成根因分析报告落库

CREATE TABLE IF NOT EXISTS diagnosis_report (
    id               BIGSERIAL PRIMARY KEY,
    alert_history_id BIGINT REFERENCES alert_history(id) ON DELETE SET NULL,
    appid            BIGINT      NOT NULL,
    rule_id          BIGINT,
    rule_name        VARCHAR(256),
    alert_level      VARCHAR(16),
    trigger_metric   VARCHAR(128),
    trigger_value    DOUBLE PRECISION,
    evidence         TEXT,               -- 证据(指标窗口摘要 + 日志指纹 TopN 原文)
    report           TEXT,               -- LLM 诊断报告(根因 + 证据 + 建议)
    status           VARCHAR(16) NOT NULL DEFAULT 'success',  -- success / degraded / failed
    error_msg        VARCHAR(512),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_diagnosis_report_appid_created
    ON diagnosis_report (appid, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_diagnosis_report_alert_history
    ON diagnosis_report (alert_history_id);

COMMENT ON TABLE diagnosis_report IS 'AI 告警诊断报告 - FIRING 触发后异步生成,LLM 输出根因分析+建议';
COMMENT ON COLUMN diagnosis_report.status IS 'success=LLM成功 / degraded=LLM失败但附原始证据 / failed=证据收集也失败';