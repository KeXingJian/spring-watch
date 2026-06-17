-- kxj: P0 dedup 计数双写 - 修复"Redis 挂了就丢 dedup 计数"问题
-- Redis 走实时去重判定(快路径),PostgreSQL 走 30s 周期持久化(慢路径,用于历史查询/重启恢复)
-- 命中策略: ON CONFLICT (appid, fingerprint) DO UPDATE 累加

CREATE TABLE IF NOT EXISTS log_dedup_count (
    id                  BIGSERIAL PRIMARY KEY,
    appid               BIGINT      NOT NULL,
    fingerprint         VARCHAR(64) NOT NULL,
    dedup_count         BIGINT      NOT NULL DEFAULT 0,
    last_seen_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_log_dedup_appid_fp UNIQUE (appid, fingerprint)
);

CREATE INDEX IF NOT EXISTS idx_log_dedup_count_appid_last_seen
    ON log_dedup_count (appid, last_seen_at DESC);

CREATE INDEX IF NOT EXISTS idx_log_dedup_count_count
    ON log_dedup_count (dedup_count DESC);

COMMENT ON TABLE  log_dedup_count IS '日志 dedup 计数双写表 - Redis 实时去重判定 + DB 周期持久化(防 Redis 挂掉丢历史)';
COMMENT ON COLUMN log_dedup_count.appid       IS '监控应用 ID';
COMMENT ON COLUMN log_dedup_count.fingerprint IS '日志指纹(sha1Hex)';
COMMENT ON COLUMN log_dedup_count.dedup_count IS '窗口内被去重丢弃的条数';
COMMENT ON COLUMN log_dedup_count.last_seen_at IS '最近一次 dedup 命中时间';
