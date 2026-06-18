#!/bin/sh
set -e

if [ "${OTEL_ENABLED}" = "true" ]; then
    echo "[mock-test] OpenTelemetry Agent enabled"
    echo "[mock-test] Service:    ${OTEL_SERVICE_NAME:-mock-test}"
    echo "[mock-test] Metrics:    :${OTEL_EXPORTER_PROMETHEUS_PORT:-9464}/metrics"
    echo "[mock-test] App port:   ${SERVER_PORT:-8081}"

    # ==== 默认开关(防御性显式开,防止 default-enabled 策略变动) ====
    # ==== JVM / Runtime / 系统级指标(关键) ====
    # ==== Web / HTTP 服务端 ====
    # ==== Web / HTTP 客户端 ====
    # ==== 数据库 / 连接池 / ORM ====
    # ==== 日志 appender + MDC 注入(给日志打 trace_id) ====
    # ==== 注解 / AOP ====
    # ==== AOT(native image,可选) ====
    # ==== 内部(internal instrumentation) ====
    # 注意:不能把 # 注释写在 exec java 的 \<换行> 续行块内部,POSIX sh 会把空行
    # / 续行块中的 # 误识别为命令结束,导致 -jar 丢参、jvm 启动后只打 usage。
    exec java ${JAVA_OPTS} \
        -javaagent:/app/opentelemetry-javaagent.jar \
        -Dotel.service.name=${OTEL_SERVICE_NAME:-mock-test} \
        -Dotel.resource.attributes=service.name=${OTEL_SERVICE_NAME:-mock-test},service.namespace=spring-watch,deployment.environment=docker \
        -Dotel.metrics.exporter=prometheus \
        -Dotel.logs.exporter=none \
        -Dotel.traces.exporter=none \
        -Dotel.exporter.prometheus.host=${OTEL_EXPORTER_PROMETHEUS_HOST:-0.0.0.0} \
        -Dotel.exporter.prometheus.port=${OTEL_EXPORTER_PROMETHEUS_PORT:-9464} \
        -Dotel.semconv-stability.opt-in=http \
        -Dotel.instrumentation.common.default-enabled=true \
        -Dotel.instrumentation.runtime-jvm.enabled=true \
        -Dotel.instrumentation.runtime-telemetry.enabled=true \
        -Dotel.instrumentation.oshi.enabled=true \
        -Dotel.instrumentation.jmx.enabled=true \
        -Dotel.instrumentation.executors.enabled=true \
        -Dotel.instrumentation.scheduling.enabled=true \
        -Dotel.instrumentation.micrometer.enabled=true \
        -Dotel.instrumentation.spring-web.enabled=true \
        -Dotel.instrumentation.spring-webmvc.enabled=true \
        -Dotel.instrumentation.spring-core.enabled=true \
        -Dotel.instrumentation.spring-scheduling.enabled=true \
        -Dotel.instrumentation.tomcat.enabled=true \
        -Dotel.instrumentation.servlet.enabled=true \
        -Dotel.instrumentation.jakarta-servlet.enabled=true \
        -Dotel.instrumentation.http-server.enabled=true \
        -Dotel.instrumentation.http-client.enabled=true \
        -Dotel.instrumentation.http-url-connection.enabled=true \
        -Dotel.instrumentation.apache-httpclient.enabled=true \
        -Dotel.instrumentation.apache-httpasyncclient.enabled=true \
        -Dotel.instrumentation.okhttp.enabled=true \
        -Dotel.instrumentation.jdbc.enabled=true \
        -Dotel.instrumentation.jakarta-jdbc.enabled=true \
        -Dotel.instrumentation.hikaricp.enabled=true \
        -Dotel.instrumentation.tomcat-jdbc.enabled=true \
        -Dotel.instrumentation.c3p0.enabled=true \
        -Dotel.instrumentation.dbcp-2.0.enabled=true \
        -Dotel.instrumentation.vibur-dbcp.enabled=true \
        -Dotel.instrumentation.hibernate.enabled=true \
        -Dotel.instrumentation.jpa.enabled=true \
        -Dotel.instrumentation.elastic-transaction-2.0.enabled=true \
        -Dotel.instrumentation.logback-appender-1.0.enabled=true \
        -Dotel.instrumentation.logback-mdc-1.0.enabled=true \
        -Dotel.instrumentation.log4j-appender-1.0.enabled=true \
        -Dotel.instrumentation.log4j-mdc-1.0.enabled=true \
        -Dotel.instrumentation.log4j2-appender-1.0.enabled=true \
        -Dotel.instrumentation.log4j2-mdc-1.0.enabled=true \
        -Dotel.instrumentation.jul-appender-1.0.enabled=true \
        -Dotel.instrumentation.jul-mdc-1.0.enabled=true \
        -Dotel.instrumentation.jboss-logmanager-appender-1.0.enabled=true \
        -Dotel.instrumentation.jboss-logmanager-mdc-1.0.enabled=true \
        -Dotel.instrumentation.annotations.enabled=true \
        -Dotel.instrumentation.code-function-metrics.enabled=true \
        -Dotel.instrumentation.aspectj.enabled=true \
        -Dotel.instrumentation.native.enabled=true \
        -Dotel.instrumentation.internal.enabled=true \
        -Dserver.port=${SERVER_PORT:-8081} \
        -jar /app/app.jar
else
    echo "[mock-test] OpenTelemetry Agent disabled, running plain Java"
    exec java ${JAVA_OPTS} -Dserver.port=${SERVER_PORT:-8081} -jar /app/app.jar
fi
