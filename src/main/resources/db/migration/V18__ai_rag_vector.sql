-- kxj: P2 RAG 知识库 - PGVector 向量表
-- 依赖: PostgreSQL 需启用 vector 扩展(enable_extension vector 由 Flyway 自动创建)
-- embedding 以 TEXT 存向量字面量(如 "[0.1,0.2,...]"),相似检索在 native SQL 里用 ::vector 强转,
-- 避免 Hibernate 7 对 vector 列的类型绑定问题,同时保留 PGVector 余弦检索能力。
-- 用途: 白皮书/故障手册切片 + 历史诊断报告回灌,支撑 AI 问答与诊断。

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id           BIGSERIAL PRIMARY KEY,
    source       VARCHAR(64)  NOT NULL,          -- 来源: 白皮书 / 故障手册 / 诊断报告 / SOP
    source_id    VARCHAR(128),                   -- 来源实体ID(如诊断报告 historyId / 文档名)
    title        VARCHAR(256),
    chunk_index  INTEGER,                        -- 切片序号
    content      TEXT NOT NULL,                  -- 切片文本
    embedding    TEXT,                           -- 向量字面量(JSON数组),native SQL 转 ::vector
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_source
    ON knowledge_chunk (source, source_id);

-- 向量检索走 native SQL(embedding::vector <=> 余弦距离),数据量小时全表扫描可接受。
-- 若需 HNSW 索引,embedding 需为固定维度 vector 类型且与 embedding 模型维度一致
-- (当前 TEXT 存储无法确定维度,故不建向量索引)。

COMMENT ON TABLE  knowledge_chunk IS 'P2 RAG 知识库切片 - 白皮书/故障手册/诊断报告,embedding 入库支撑相似检索';
COMMENT ON COLUMN knowledge_chunk.embedding IS '文本向量(TEXT 存向量字面量),检索时 ::vector 强转走余弦相似';
