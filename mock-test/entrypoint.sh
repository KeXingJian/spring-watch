#!/bin/sh

JAVA_OPTS="${JAVA_OPTS:=-Xmx256m}"

if [ "${OTEL_ENABLED}" = "true" ]; then
    echo "[mock-test] OpenTelemetry Agent enabled, Prometheus exporter on port ${OTEL_EXPORTER_PROMETHEUS_PORT:-9464}"
    exec java ${JAVA_OPTS} \
        -javaagent:/app/opentelemetry-javaagent.jar \
        -Dotel.service.name=${OTEL_SERVICE_NAME:-mock-test} \
        -Dotel.metrics.exporter=prometheus \
        -Dotel.logs.exporter=logging \
        -Dotel.traces.exporter=logging \
        -Dotel.exporter.prometheus.port=${OTEL_EXPORTER_PROMETHEUS_PORT:-9464} \
        -Dotel.exporter.prometheus.host=0.0.0.0 \
        -Dotel.resource.attributes=service.name=${OTEL_SERVICE_NAME:-mock-test},service.namespace=spring-watch \
        -jar /app/app.jar
else
    exec java ${JAVA_OPTS} -jar /app/app.jar
fi