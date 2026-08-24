-- kxj: AI 模块会话/消息表(照搬 HertzBeat ChatConversation/ChatMessage)
-- 会话短期放 Caffeine,持久化放 PG,供历史追溯

CREATE TABLE IF NOT EXISTS chat_conversation (
    id          BIGSERIAL PRIMARY KEY,
    title       VARCHAR(128) NOT NULL DEFAULT '新会话',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS chat_message (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES chat_conversation(id) ON DELETE CASCADE,
    role            VARCHAR(16) NOT NULL,   -- user / assistant / system_push
    content         TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_chat_message_conv_created
    ON chat_message (conversation_id, created_at);

COMMENT ON TABLE  chat_conversation IS 'AI 对话会话表';
COMMENT ON TABLE  chat_message        IS 'AI 对话消息表 - role: user/assistant/system_push';