# spring-watch 渲染层(新)

> 旧版是 `src/main/resources/static/{index.html,pages/*}` 静态页 + Spring Boot 嵌入式 Tomcat 兜底;
> 本目录是新版 **Vite + Vue 3 + TypeScript + Pinia + Vue Router + ECharts** 重构。

## 开发

```bash
cd frontend
npm install
npm run dev
# 打开 http://localhost:5173
# Vite dev server 已把 /api/* 代理到 http://localhost:8080
```

需要后端先起来:

```bash
docker compose up -d        # 启动 PG/Redis/Kafka/InfluxDB
mvn spring-boot:run         # 或者 mvn package && java -jar target/spring-watch-1.0.0.jar
```

## 生产构建

```bash
npm run build                # 产物在 frontend/dist/
```

构建产物可直接作为 SPA 静态资源部署,后端 `/api/*` 仍由 Spring Boot 提供。

## 目录结构

```
frontend/
├── src/
│   ├── api/             # 后端 HTTP 客户端(fetch 封装,统一处理 {code,data} 包装)
│   ├── charts/          # ECharts 组件:LineChart / BarChart / PieChart / Chart
│   ├── components/      # 通用组件:Layout / Modal / EmptyState / MetricCard / ToastHost
│   ├── composables/     # 组合式函数:metrics (拉数据/格式化/分位等)
│   ├── router/          # Vue Router 4
│   ├── stores/          # Pinia:app (当前 appid / 应用列表)
│   ├── utils/           # format / toast
│   ├── views/           # 业务页面
│   │   ├── AppsView.vue
│   │   ├── AppDetailView.vue
│   │   ├── appdetail/   # JDBC / HTTP / JVM / OS 四个 Tab 拆分子组件
│   │   ├── LogsView.vue
│   │   ├── AlertRulesView.vue
│   │   ├── AlertHistoryView.vue
│   │   ├── EmailConfigView.vue
│   │   └── SelfMonitorView.vue
│   ├── App.vue
│   ├── main.ts
│   └── style.css        # 与旧 style.css 保持变量兼容
├── index.html
├── package.json
├── tsconfig.json
└── vite.config.ts       # 含 /api 代理配置
```

## 与旧版的差异

| 维度 | 旧 | 新 |
|------|----|----|
| 壳 | iframe 套 8 个 HTML | Vue Router 4 SPA |
| 状态 | localStorage + DOM | Pinia + localStorage 持久 |
| API | window.SW.api (全局) | `@/api/client` 模块化 + TS 类型 |
| 图表 | ECharts 实例 + 手动 dispose | `vue-echarts` + `autoresize` |
| 类型 | 纯 JS | TypeScript |
| 构建 | 无 | Vite 6 |
| UI 库 | 自写 CSS | 沿用同一套 CSS 变量,自写小组件 |

## 升级说明

1. 旧 `src/main/resources/static/{index.html,pages/*}` 在新前端接入后即可删除;
2. 若想"上线即单 jar",可将 `frontend/dist/` 复制到 `src/main/resources/static/`,Spring Boot 默认兜底;
3. 路由表见 `src/router/index.ts`,新增页面在那里加一行。
