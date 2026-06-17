-- kxj: P0 收尾 - 旧单列索引被 V12.1 复合索引覆盖,冗余移除
DROP INDEX IF EXISTS idx_alert_history_appid;

-- ============================================================
-- kxj: P0 DLQ 消息落库 - 修复"消费失败的消息无法查询/重投"
-- 数据流: Consumer失败 → DefaultErrorHandler → DeadLetterPublishingRecoverer
--         → 写 .DLQ topic → 本表 + 由 DlqMonitorConsumer 消费
-- 重投策略: API 端手动把 replayed=false 改成 true + 回灌原 topic
-- ============================================================
CREATE TABLE IF NOT EXISTS dlq_message (
    id                  BIGSERIAL PRIMARY KEY,
    source_topic        VARCHAR(128) NOT NULL,
    original_partition  INTEGER,
    original_offset     BIGINT,
    original_timestamp  TIMESTAMPTZ,
    payload             TEXT,
    key                 VARCHAR(256),
    error_fqcn          VARCHAR(256),
    error_cause_fqcn    VARCHAR(256),
    error_message       TEXT,
    error_stacktrace    TEXT,
    replayed            BOOLEAN      NOT NULL DEFAULT FALSE,
    replayed_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_dlq_source_topic_created
    ON dlq_message (source_topic, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_dlq_replayed_created
    ON dlq_message (replayed, created_at DESC);

COMMENT ON TABLE  dlq_message IS 'Kafka DLQ 消息落库 - 消费失败后 DeadLetterPublishingRecoverer 写入 .DLQ topic,本表持久化以便查询/重投';
COMMENT ON COLUMN dlq_message.source_topic       IS '失败消息所属原始 topic';
COMMENT ON COLUMN dlq_message.original_partition IS '原始 topic 的 partition';
COMMENT ON COLUMN dlq_message.original_offset    IS '原始 topic 的 offset,用于精确重投';
COMMENT ON COLUMN dlq_message.error_stacktrace   IS '失败时的堆栈,截断前 8KB';
COMMENT ON COLUMN dlq_message.replayed           IS '是否已重投,默认 FALSE,人工/API 触发回灌后置 TRUE';
