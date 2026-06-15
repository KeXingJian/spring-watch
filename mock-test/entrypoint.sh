#!/bin/sh
set -e

if [ "${OTEL_ENABLED}" = "true" ]; then
    echo "[mock-test] OpenTelemetry Agent enabled"
    echo "[mock-test] Service:    ${OTEL_SERVICE_NAME:-mock-test}"
    echo "[mock-test] Metrics:    :${OTEL_EXPORTER_PROMETHEUS_PORT:-9464}/metrics"
    echo "[mock-test] App port:   ${SERVER_PORT:-8081}"

    exec java ${JAVA_OPTS} \
        -javaagent:/app/opentelemetry-javaagent.jar \
        -Dotel.service.name=${OTEL_SERVICE_NAME:-mock-test} \
        -Dotel.resource.attributes=service.name=${OTEL_SERVICE_NAME:-mock-test},service.namespace=spring-watch,deployment.environment=docker \
        -Dotel.metrics.exporter=prometheus \
        -Dotel.logs.exporter=none \
        -Dotel.traces.exporter=none \
        -Dotel.exporter.prometheus.host=${OTEL_EXPORTER_PROMETHEUS_HOST:-0.0.0.0} \
        -Dotel.exporter.prometheus.port=${OTEL_EXPORTER_PROMETHEUS_PORT:-9464} \
        -Dotel.instrumentation.common.default-enabled=true \
        -Dotel.instrumentation.http-server.enabled=false \
        -Dotel.instrumentation.servlet.enabled=false \
        -Dotel.instrumentation.spring-web.enabled=false \
        -Dotel.instrumentation.spring-webmvc.enabled=false \
        -Dotel.instrumentation.tomcat.enabled=false \
        -Dotel.instrumentation.jdbc.enabled=false \
        -Dotel.instrumentation.hikari.enabled=false \
        -Dotel.instrumentation.annotations.enabled=false \
        -Dotel.instrumentation.code-function-metrics.enabled=false \
        -Dotel.instrumentation.logback-appender.enabled=true \
        -Dserver.port=${SERVER_PORT:-8081} \
        -jar /app/app.jar
else
    echo "[mock-test] OpenTelemetry Agent disabled, running plain Java"
    exec java ${JAVA_OPTS} -Dserver.port=${SERVER_PORT:-8081} -jar /app/app.jar
fi
