# spring-watch 接入 AI 建议方案

> 基于 HertzBeat AI 模块(`hertzbeat-ai`)的架构经验,为 spring-watch 定制 AI 增强路线。

---

## 1. 项目现状对比

### 1.1 spring-watch 与 HertzBeat 关键差异

```mermaid
flowchart LR
    subgraph HB["HertzBeat AI 模块"]
        HB1["多租户 + 安全表单<br/>密码类敏感参数多"]
        HB2[Angular 前端]
        HB3[六大通用工具<br/>监控/告警/指标/技能/调度]
        HB4[多实例集群<br/>Netty RPC]
    end

    subgraph SW["spring-watch"]
        SW1[无敏感参数<br/>MonitorApp 仅 endpoint/port]
        SW2[Vue 3 前端]
        SW3["日志指纹去重 + 异常检测<br/>日志-指标关联(独家优势)"]
        SW4["单实例 + Caffeine<br/>SSE 天然适配"]
    end
```

| 维度 | HertzBeat AI | spring-watch | 影响 |
|------|-------------|--------------|------|
| 技术栈 | Spring Boot 4.0.3 + Spring AI 1.1.1 | Spring Boot 4.0.1 | 版本可直接照搬 |
| 敏感参数 | 密码/token 多,需 SecureForm + AES | 无,仅 endpoint/port | **安全协议可大幅简化** |
| 数据特色 | 通用监控模板 | 日志指纹 + 异常检测 + 日志-指标关联 | AI 分析原料更值钱 |
| 部署形态 | 多实例 | 单实例 + Caffeine | 无需共享会话状态 |
| 前端 | Angular 17 | Vite + Vue 3 + TS | 聊天面板更轻 |

---

## 2. 建议的 AI 能力分层

```mermaid
flowchart TB
    subgraph L1["第1层: AI 对话助手(最小可用)"]
        A1[ChatController<br/>SSE 流式] --> A2[ConversationService<br/>会话持久化]
        A2 --> A3[ChatClient<br/>Spring AI OpenAI]
        A3 --> A4[系统提示词<br/>注入领域知识]
    end

    subgraph L2["第2层: 工具调用(核心价值)"]
        B1[5 个 @Tool 工具集] --> B2[HertzBeat 模式<br/>MethodToolCallbackProvider]
    end

    subgraph L3["第3层: SOP 技能引擎(差异化亮点)"]
        C1[SopEngine<br/>同步/异步执行] --> C2[YAML 技能文件]
        C2 --> C3[Tool 步骤: 数据采集]
        C2 --> C4[LLM 步骤: 分析报告]
    end

    subgraph L4["第4层: 定时巡检 + MCP"]
        D1[AI 创建 cron 任务<br/>SopSchedule] --> D2[结果推送回会话]
        D3[MCP Server<br/>/api/mcp 对外暴露]
    end

    A3 --> B1
    B1 --> L3
```

### 2.1 工具映射表(直接复用 HertzBeat 模式)

| HertzBeat 工具 | spring-watch 对应工具 | 落点(现有类) |
|---|---|---|
| MonitorTools | 应用注册/启停/列表 | `MonitorAppService` |
| MetricsTools | 指标最新值/时序查询 | `MetricQueryService.queryLatest / querySeries` |
| LogTools | 日志检索/指纹 TopN/错误率 | `LogQueryService.search / topFingerprints / errorRateSeries` |
| AlertTools | 告警规则 CRUD/历史查询 | `AlertRuleService` / `AlertEngine` |
| ScheduleTools | AI 定时巡检 | 新增 `SopSchedule` 表 + 复用 `@Scheduled` |

---

## 3. 差异化 SOP 技能场景

```mermaid
flowchart TB
    subgraph S1["技能1: JVM OOM 诊断"]
        J1[堆内存曲线<br/>tool] --> J2[GC 指标<br/>tool] --> J3[生成诊断报告<br/>llm]
    end

    subgraph S2["技能2: 错误率突增排查"]
        E1[errorRateSeries<br/>tool] --> E2[异常日志指纹<br/>tool] --> E3[关联 HTTP 指标<br/>tool] --> E4[根因分析报告<br/>llm]
    end

    subgraph S3["技能3: 定时晨报"]
        M1[昨晚异常汇总<br/>tool] --> M2[告警历史<br/>tool] --> M3[生成日报<br/>llm] --> M4[推回会话<br/>system_push]
    end
```

- 日志指纹去重 + 异常检测是现成的 AI 分析素材(其他监控平台没有)
- 每个技能 = 1 个 YAML 文件,`tool` 步骤采集数据、`llm` 步骤生成报告,无需改代码

---

## 4. 需要特别处理的点

| 关注点 | 说明 | 处理方式 |
|---|---|---|
| 日志隐私 | 日志可能含业务敏感数据 | 提示词约束脱敏优先,复用现有脱敏管道 |
| InfluxDB 查询安全 | 防 LLM 注入 | 工具层只暴露白名单查询方法,禁止透传原始 Flux 语句 |
| 成本控制 | 防原始日志流直喂 LLM | 先做 TopN/指纹聚合,再喂 LLM |
| 安全协议简化 | spring-watch 无密码类参数 | 不需要 HertzBeat 的 SecureForm + AES,agent 接入方式用保护提示词即可 |
| 前端 | Vue 3 生态 | chat 面板 + EventSource 消费 SSE,比 Angular 更轻 |

---

## 5. 推荐落地顺序

```mermaid
flowchart LR
    S1[第1步<br/>加 spring-ai-openai<br/>SSE 聊天<br/>半天] --> S2[第2步<br/>注册 5 个 @Tool<br/>查指标/日志/告警<br/>1天]
    S2 --> S3[第3步<br/>SopEngine + 2 个 YAML 技能<br/>daily_report / error_surge<br/>1天]
    S3 --> S4[第4步<br/>定时巡检推送<br/>复用 @Scheduled]
    S4 --> S5[第5步<br/>MCP Server 对外暴露]
```

> 建议直接从**第2步工具调用**做起,数据基础好(日志指纹 + 异常检测),效果立竿见影。

---

## 6. 新增表结构建议

```sql
-- 会话表(照搬 HertzBeat ChatConversation)
CREATE TABLE chat_conversation (
    id          BIGSERIAL PRIMARY KEY,
    title       VARCHAR(128),
    created_at  TIMESTAMP DEFAULT now()
);

-- 消息表(照搬 HertzBeat ChatMessage)
CREATE TABLE chat_message (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES chat_conversation(id),
    role            VARCHAR(16),   -- user / assistant / system_push
    content         TEXT,
    created_at      TIMESTAMP DEFAULT now()
);

-- 定时巡检表(照搬 HertzBeat SopSchedule)
CREATE TABLE sop_schedule (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES chat_conversation(id),
    sop_name        VARCHAR(64),   -- 技能名: daily_report / error_surge_diagnosis
    cron_expression VARCHAR(64),
    enabled         BOOLEAN DEFAULT true,
    last_run_time   TIMESTAMP,
    next_run_time   TIMESTAMP
);
```

---

## 7. 依赖引入(照搬 HertzBeat pom)

```xml
<!-- spring-ai-bom 1.1.1,兼容 Spring Boot 4.0.x -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-openai</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-client-chat</artifactId>
</dependency>
```