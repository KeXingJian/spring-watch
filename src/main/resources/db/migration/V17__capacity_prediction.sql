-- kxj: P2 容量预测 - 容量预测结果落库
-- 背景: 对关键指标(内存/磁盘/CPU)做统计趋势预测,LLM 仅解释,预测结果供前端展示与历史追溯

CREATE TABLE IF NOT EXISTS capacity_prediction (
    id               BIGSERIAL PRIMARY KEY,
    appid            BIGINT      NOT NULL,
    app_name         VARCHAR(128),
    metric           VARCHAR(128) NOT NULL,
    horizon_hours    INTEGER     NOT NULL DEFAULT 24,   -- 预测前瞻窗口(小时)
    current_value    DOUBLE PRECISION,                  -- 当前最新值
    predicted_value  DOUBLE PRECISION,                  -- 预测值(线性/滑动趋势)
    growth_rate      DOUBLE PRECISION,                  -- 趋势斜率(单位/小时)
    confidence       VARCHAR(16),                       -- high / medium / low
    scenario         VARCHAR(32),                       -- OOM / DISK / CPU / OTHER
    risk_level       VARCHAR(16),                       -- safe / warning / critical
    explanation      TEXT,                              -- LLM 解释(可选)
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_capacity_prediction_app_created
    ON capacity_prediction (appid, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_capacity_prediction_metric
    ON capacity_prediction (metric, created_at DESC);

COMMENT ON TABLE  capacity_prediction IS 'P2 容量预测结果 - 统计模型先行,LLM 解释,供前端展示';
COMMENT ON COLUMN capacity_prediction.scenario   IS '预测场景: OOM / DISK / CPU / OTHER';
COMMENT ON COLUMN capacity_prediction.risk_level IS '风险等级: safe / warning / critical';
COMMENT ON COLUMN capacity_prediction.confidence IS '预测置信度: high / medium / low';
